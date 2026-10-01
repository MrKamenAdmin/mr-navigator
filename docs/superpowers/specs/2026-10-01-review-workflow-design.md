# Ревью-воркфлоу: черновики, request changes, CI, merge, правка комментариев

Статус: дизайн одобрен в чате 2026-10-01. Основа — версия 0.3.1 (спека `2026-10-01-github-bitbucket-i18n-design.md`).

## Цель

Восемь улучшений поверх четырёх провайдеров (GitLab, GitHub, Bitbucket Cloud, Bitbucket DC):

1. **Локальный merge base** — GitHub и Bitbucket Cloud не спрашивают merge base у API; diff PR из форков Bitbucket Cloud
   начинает работать, тяжёлый `/compare` GitHub уходит.
2. **Кириллица в путях raw diff** Bitbucket (`"\320\237…"`) декодируется.
3. **Черновики и ревью пачкой** — комментарии копятся локально и отправляются одним ревью с итоговым текстом и вердиктом;
   недописанный текст попапа не теряется.
4. **Request changes** рядом с Approve; в карточке видно, кто одобрил и кто запросил изменения.
5. **Устаревшие треды на полях diff** (GitLab, GitHub) — на строке, куда она переехала.
6. **Полный Markdown в комментариях**.
7. **Правка и удаление своих комментариев**.
8. **Статус CI и кнопка Merge** в карточке.

Успех: на каждом провайдере работает то, что позволяет его API (матрица ниже); недоступное скрыто, а не падает.

## Не цели

- Черновые ответы в существующие треды — ответ уходит сразу, как сейчас.
- Серверные черновики, видимые в вебе до отправки (GitLab draft notes, GitHub pending review, DC pending) — используются
  только в момент отправки ревью.
- Auto-merge, merge queue, сообщение merge-коммита, удаление ветки в Bitbucket DC.
- Перенос устаревших тредов на левой стороне diff и в Bitbucket (API не отдаёт коммит, на котором написан комментарий).
- Опрос CI по таймеру — статус обновляется вместе с MR.

## Матрица

| | GitLab | GitHub | Bitbucket Cloud | Bitbucket DC |
|---|---|---|---|---|
| Merge base | API (`diff_refs`) | локально | локально | API (`merge-base`) |
| Отправка ревью | `draft_notes` → `bulk_publish` (+ approve / request changes) | `POST reviews` (`comments[]`, `event`, `body`) | по очереди + вердикт | `state: PENDING` → `PUT /review` |
| Уведомлений за ревью | одно | одно | по одному на комментарий | одно |
| Request changes | GraphQL `mergeRequestRequestChanges` | review `REQUEST_CHANGES` | `POST /request-changes` | статус `NEEDS_WORK` |
| Снять запрос | GraphQL `mergeRequestDestroyRequestedChanges` | dismiss своего ревью | `DELETE /request-changes` | статус `UNAPPROVED` |
| Вердикты ревьюеров | approvals + `GET /reviewers` | последнее ревью каждого | `participants[].state` | `reviewers[].status` |
| Правка / удаление | `PUT`/`DELETE …/discussions/:d/notes/:n` | `PATCH`/`DELETE` pulls или issues comments | `PUT`/`DELETE …/comments/:id` | `PUT` (с `version`) / `DELETE ?version=` |
| Устаревшие треды на полях | ✓ (`position.head_sha`) | ✓ (`originalCommit.oid`) | — | — |
| CI | `head_pipeline` | check-runs + statuses | `…/pullrequests/:id/statuses` | `/rest/build-status/latest/commits/:sha` |
| Merge | `PUT …/merge` (squash, удалить ветку) | `PUT …/merge` (`merge_method`), ветку удаляем сами | `POST …/merge` (`merge_strategy`, `close_source_branch`; 202 → опрос) | `POST …/merge?version=` (`strategyId`) |
| Стратегии merge | `squash_option` проекта | `allow_*` репозитория | `destination.branch.merge_strategies` | `settings/pull-requests` → `mergeConfig.strategies` |
| Причина блокировки | `detailed_merge_status` | `mergeable == false` / `mergeable_state` | — | `vetoes[]` / `outcome` |

## Модели и интерфейс

```kotlin
// baseSha == null — сервер его не даёт (GitHub, Bitbucket Cloud): считается локально после fetch.
data class DiffRefs(val baseSha: String?, val startSha: String, val headSha: String)

enum class Verdict { COMMENT, APPROVE, REQUEST_CHANGES }
/** Usernames by their current verdict. */
data class Reviews(val approved: List<String>, val changesRequested: List<String>)
/** A comment kept in the IDE until the review is submitted. */
data class Draft(val id: String, val body: String, val position: Position)

enum class CiState { SUCCESS, FAILED, RUNNING, NONE }
data class Check(val name: String, val state: CiState, val url: String?)
data class Checks(val state: CiState, val url: String?, val items: List<Check>)   // state — свёртка items

data class MergeStrategy(val id: String, val title: String)
data class MergeOptions(val strategies: List<MergeStrategy>, val defaultStrategy: String?, val canDeleteBranch: Boolean, val blocker: String?)
```

`HostingClient`: `approvedBy` заменяется на `reviews(project, mr): Reviews`; добавляются
`submitReview(project, mr, drafts, verdict, summary)`, `withdrawChanges(project, mr)`,
`editNote(project, mr, d, note, body)`, `deleteNote(project, mr, d, note)`, `checks(project, mr): Checks`,
`mergeOptions(project, mr): MergeOptions`, `merge(project, mr, strategy: String?, deleteBranch: Boolean)`.
`approve`/`unapprove` остаются (Approve — в один клик). Свёртка CI: есть FAILED → FAILED, иначе есть RUNNING → RUNNING,
иначе есть хоть одна проверка → SUCCESS, иначе NONE.

## Детали

### 1. Локальный merge base
- `DiffLineMap.position(baseSha: String?, …)`; `MrSession.refs` возвращает `DiffRefs` с заполненным `baseSha`:
  `mr.diffRefs.baseSha ?: localBase ?: ошибка error.noMergeBase`.
- `ensureCommits`: fetch head (как сейчас); если нет `startSha` — fetch целевой ветки; если `baseSha == null` —
  `git merge-base startSha headSha` → `s.localBase`.
- GitHub: без `/compare`; `DiffRefs(null, base.sha, head.sha)`. Bitbucket Cloud: без `merge-base`; полные хэши head
  (из списка коммитов PR) и destination (`/commit/{dst}`) — как сейчас.

### 2. Escape в путях
`UnifiedDiff.unquote`: строка в кавычках раскрывается как C-строка git (`\\`, `\"`, `\t`, `\n`, `\NNN` — октальные байты
UTF-8).

### 3. Черновики и ревью
- Хранение: `MrReviewService` держит черновики сессии и сохраняет их в `PropertiesComponent` под ключом MR
  (`…drafts.<project>!<iid>`), JSON-список `{id, body, position}`; `position` — `Position.toJson()` и обратно
  `Position.from`. Кодирование — чистые функции `Drafts.encode/decode`.
- UI: в форме нового комментария на строке — кнопки «Комментировать» и «В ревью»; черновик на полях — своя иконка,
  клик — попап с текстом черновика: «Сохранить», «Удалить». В карточке — кнопка «Ревью (N)…» (видна при N > 0),
  а «Запросить изменения…» открывает тот же диалог с вердиктом REQUEST_CHANGES.
- Диалог отправки: список черновиков (файл:строка, начало текста), итоговый комментарий, вердикт (Комментарий /
  Approve / Запросить изменения), «Отправить», «Удалить черновики». Для REQUEST_CHANGES итоговый текст обязателен.
- Отправка: GitHub — `POST …/reviews {commit_id, event, body?, comments:[{path, body, line, side, start_line?, start_side?}]}`
  (`body` не шлётся, если пуст). GitLab — `POST draft_notes {note, position}` на каждый черновик и `{note}` для итогового
  текста, затем `POST draft_notes/bulk_publish`, затем approve или GraphQL request changes. DC — `POST comments {…, state: PENDING}`
  на каждый, затем `PUT …/review?version=<версия PR> {commentText, participantStatus: APPROVED|NEEDS_WORK}` (статус
  не шлётся для COMMENT). Bitbucket Cloud — `createDiscussion` по очереди, итоговый текст общим комментарием, затем
  approve или `POST /request-changes`. Успех — черновики удаляются.
- Недописанный текст: `ThreadPopup` держит в памяти текст закрытых без отправки попапов по ключу (тред, строка файла
  или общий комментарий) и подставляет при повторном открытии.

### 4. Request changes
- Кнопка «Запросить изменения…» / «Снять запрос изменений» (если я среди `changesRequested`) рядом с Approve.
- В meta-строке карточки: `approved: …` и `changes requested: …`.
- GitLab GraphQL: `<url>/api/graphql`, `Authorization: Bearer <token>`.

### 5. Устаревшие треды
- GitHub: в GraphQL у комментариев запрашивается `originalCommit { oid }`; для устаревшего треда `Position.headSha` —
  oid первого комментария.
- `MrDiffOpener.Producer` (фон) для устаревших тредов правой стороны открываемого файла: если старый коммит есть
  локально — `git diff -U0 <старый head> <head> -- <путь>`, `DiffLineMap(diff).newFor(строка)`; результат — в
  `MrSession.relocated[discussionId]`. Чистая функция `Relocate.line(diff, line): Int?`.
- `CommentMarkers` рисует такие треды на перенесённой строке блёклой иконкой; тултип с пометкой «устарел».

### 6. Markdown в комментариях
`ThreadPopup.htmlBody` → `Markdown.gfmToHtml(body, projectWebUrl)`; блок `suggestion…` получает подпись «Suggestion:»
(пост-обработка `<pre><code class="language-suggestion…">`). `Markdown.toHtml` удаляется вместе с тестами, тесты подписи
переносятся на `gfmToHtml`.

### 7. Правка и удаление
- В заголовке своей заметки (автор == текущий пользователь) — ссылки «Изменить» и «Удалить». Правка — текст заметки
  заменяется полем с «Сохранить» / «Отмена»; удаление — подтверждение.
- GitHub отличает issue-комментарии по префиксу id треда `issue:`; DC берёт `version` через `GET` комментария.

### 8. CI и Merge
- `checks` грузится в `loadSession` (ошибка → нет строки CI). Строка в карточке: иконка состояния + «CI: …», клик —
  ссылка (`url`), тултип — список проверок.
- «Merge…» (MR открыт): фон — `mergeOptions`; диалог — причина блокировки (если есть), стратегия (combo из разрешённых,
  по умолчанию `defaultStrategy`), «Удалить ветку» (если `canDeleteBranch`), «Merge»; успех — уведомление и обновление MR.
- Стратегии: GitLab — `squash_option` проекта (`never` → merge, `always` → squash, иначе обе; по умолчанию squash при
  `default_on`); GitHub — `allow_merge_commit/allow_squash_merge/allow_rebase_merge` → merge/squash/rebase, удалить
  ветку — только из того же репозитория (`DELETE …/git/refs/heads/<ветка>` после merge); Bitbucket Cloud —
  `merge_strategies` и `default_merge_strategy` ветки назначения, 202 → опрос `Location` до `SUCCESS` (≤ 60 с);
  DC — включённые `mergeConfig.strategies` (`id`, `name`), `defaultStrategy`, версия PR из `GET` PR.

## Тестирование

Юнит-тесты (JUnit 4, без IDE):
- `UnifiedDiff` с путями в escape;
- payload'ы отправки ревью каждого провайдера;
- разбор вердиктов, CI и вариантов merge каждого провайдера;
- `Drafts.encode/decode`;
- `Relocate.line`;
- подпись suggestion в `gfmToHtml`;
- свёртка CI;
- бандл: ключи новых строк в обоих языках (существующие тесты).

Без тестов: UI, живые API. Самые рискованные места: GitHub-ревью без итогового текста (в доке `body` обязателен для
COMMENT), асинхронный merge Bitbucket Cloud, `participantStatus` в DC `/review` (в схеме без enum), GitLab GraphQL с
PAT через Bearer.

## Фазы

Ветка `review-workflow`, каждая фаза — коммит, после каждой `./gradlew test buildPlugin` зелёный.

1. Escape в путях + локальный merge base.
2. Markdown в комментариях.
3. Правка и удаление.
4. Вердикты, request changes, черновики и отправка ревью (кнопка «Запросить изменения…» открывает диалог ревью,
   поэтому одна фаза).
5. Устаревшие треды на полях.
6. CI и Merge.
7. README, CHANGELOG, версия 0.4.0.
