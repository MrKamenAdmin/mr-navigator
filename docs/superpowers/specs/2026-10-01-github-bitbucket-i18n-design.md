# GitHub, Bitbucket Cloud, Bitbucket Data Center и английский UI

Статус: дизайн одобрен в чате 2026-10-01. Фаза 0 (Markdown в описании) уже сделана — коммит 4c55a9a.

## Цель

Плагин сейчас работает только с GitLab и только по-русски. Нужно:

1. ревью pull request'ов GitHub (github.com и Enterprise Server), Bitbucket Cloud и Bitbucket Data Center с тем же
   сценарием, что для GitLab: список → checkout → diff с навигацией → комментарии → approve;
2. несколько подключений одновременно: провайдер выбирается по хосту git remote, рабочий GitLab и личный GitHub
   работают без перенастройки;
3. английский интерфейс плюс русский, язык выбирается в настройках.

Успех: на каждом из четырёх провайдеров работает список с фильтрами, checkout, diff с комментариями в гуттере,
новый/ответный комментарий, resolve (где есть API), approve. Существующие пользователи GitLab после обновления
ничего не перенастраивают.

## Не цели

- Отклонение suggestion — ни у одного провайдера нет API (решено в чате).
- Применение suggestion вне GitLab: у GitHub и Bitbucket Cloud нет API; у Bitbucket DC эндпоинт есть, но синтаксис
  suggestion не документирован.
- Вставка suggestion в Bitbucket (Cloud и DC) — синтаксис не документирован.
- Merge, создание PR, черновые ревью GitHub (pending review).
- Командные review request'ы GitHub (`requested_teams`) в фильтре «Ждут моего ревью».
- SSH-алиасы хостов (`Host github-work` в `~/.ssh/config`) — remote с таким хостом не сопоставится.

## Возможности по провайдерам

| | GitLab | GitHub | Bitbucket Cloud | Bitbucket DC |
|---|---|---|---|---|
| Фильтры | все 5 | все 5 | без ASSIGNED | без ASSIGNED |
| Номер | `!12` | `#12` | `#12` | `#12` |
| Термин | MR | PR | PR | PR |
| Fetch | `refs/merge-requests/N/head` | `refs/pull/N/head` | `refs/heads/<source>`; форк — по https-URL форка | `refs/pull-requests/N/from` |
| Комментарии вне ханков | ✓ | — | — | — |
| Многострочные | ✓ | ✓ | ✓ | ✓ best-effort (`multilineMarker`) |
| Resolve | ✓ | ✓ GraphQL | ✓ | ✓ best-effort (PUT `threadResolved`) |
| Вставить suggestion | ✓ `-N+0` | ✓ без `-N+0` | — | — |
| Применить suggestion | ✓ | — | — | — |
| Approve / отозвать | ✓ / ✓ | ✓ / dismiss (нужны права write) | ✓ / ✓ | ✓ / ✓ |
| Авторизация | `PRIVATE-TOKEN` | `Bearer` | Basic `email:api-token`, без email — `Bearer` (access token) | `Bearer`, с username — Basic `user:token` |

## Архитектура

### Тип провайдера — `api/Hosting.kt` (новый)

```kotlin
enum class HostingType(
    val title: String,                // "GitLab", "GitHub", "Bitbucket Cloud", "Bitbucket Data Center"
    val prefix: Char,                 // '!' или '#'
    val term: String,                 // "MR" / "PR" — не переводится
    val filters: List<MrFilter>,
    val canSuggest: Boolean,          // кнопка «Предложить изменение»
    val commentsOutsideHunks: Boolean,
    val usernameLabel: String?,       // ключ бандла для поля username; null — поле скрыто
    val fixedUrl: String?,            // "https://bitbucket.org" для Cloud, иначе null
) {
    fun client(c: Connection, token: String): HostingClient
    fun tokenPageUrl(serverUrl: String): String
    companion object { fun guess(host: String): HostingType }
}
```

`guess`: `github.com` или хост содержит `github` → GITHUB; `bitbucket.org` → BITBUCKET_CLOUD; хост содержит
`bitbucket` → BITBUCKET_SERVER; иначе GITLAB.

Страницы создания токена:
- GitLab — как сейчас (`/-/user_settings/personal_access_tokens?name=MR+Navigator&scopes=api`);
- GitHub — `<web>/settings/tokens/new?description=MR%20Navigator&scopes=repo` (classic PAT; `<web>` = github.com или
  адрес GHE);
- Bitbucket Cloud — `https://id.atlassian.com/manage-profile/security/api-tokens`;
- Bitbucket DC — `<url>/account` (раздел HTTP access tokens; точный путь не документирован).

`MrFilter` переезжает сюда же без изменений (кроме локализации заголовков).

### Клиент — интерфейс `HostingClient`

Методы — нынешние методы `GitLabClient`; где провайдеру нужны данные MR (head sha, ветка), передаётся
`MergeRequest`, а не `iid`:

```kotlin
interface HostingClient {
    fun currentUser(): User
    fun mergeRequests(project: ProjectRef, filter: MrFilter, me: User?, search: String?): List<MergeRequest>
    fun mergeRequest(project: ProjectRef, iid: Long): MergeRequest
    fun changes(project: ProjectRef, mr: MergeRequest): List<FileChange>
    fun discussions(project: ProjectRef, mr: MergeRequest): List<Discussion>
    fun createDiscussion(project: ProjectRef, mr: MergeRequest, body: String, position: Position?)
    fun reply(project: ProjectRef, mr: MergeRequest, d: Discussion, body: String)
    fun resolve(project: ProjectRef, mr: MergeRequest, d: Discussion, resolved: Boolean)
    fun approve(project: ProjectRef, mr: MergeRequest)
    fun unapprove(project: ProjectRef, mr: MergeRequest)
    fun approvedBy(project: ProjectRef, mr: MergeRequest): List<String>
    /** Только GitLab; остальные бросают UnsupportedOperationException (кнопки нет). */
    fun applySuggestions(ids: List<Long>)
}
```

Реализации: `GitLabClient` (существующий), `GitHubClient`, `BitbucketCloudClient`, `BitbucketServerClient`.

### HTTP — `api/Http.kt` (новый)

Выносится из `GitLabClient.call()`: `HttpRequests`, таймауты, `isReadResponseOnError`, тело JSON, разбор ошибки,
описание по статусу. Параметры: `provider: String` (для текста ошибки), `auth: (URLConnection) -> Unit`.
Возвращает тело и заголовки ответа (GitLab `X-Next-Page`, GitHub `Link`, DC `X-AUSERNAME`). Пагинацию каждый
клиент делает сам: GitLab — `X-Next-Page`, GitHub — `Link: rel="next"`, Bitbucket Cloud — поле `next`, DC —
`isLastPage`/`nextPageStart`. Лимит списка MR — 200, как сейчас.

`GitLabException` переименовывается в `ApiException` (UI проверяет `status == 401`).

### Модели — `api/Models.kt`

- `MergeRequest`: удаляются неиспользуемые `id`, `projectId`, `sourceProjectId`; добавляются
  `fetchRef: String` и `fetchUrl: String?` (null — remote репозитория). GitLab заполняет
  `refs/merge-requests/<iid>/head`.
- `DiffRefs` без изменений по смыслу: `baseSha` — merge base, `startSha` — голова целевой ветки, `headSha` —
  голова PR. Все — полные SHA.
- `Position`: добавляется `outdated: Boolean = false`. Проверка «устарел» сводится в одну функцию
  `MrSession.isOutdated(d)` = `p.outdated || headSha позиции != headSha MR` и используется в `MrDetailsPanel` и
  `CommentMarkers` вместо двух копий.
- `Discussion`: добавляется `webUrl: String?` (ссылка «Открыть в браузере» в треде). GitLab заполняет
  `mr.webUrl + "#note_<id>"`.
- `User` без изменений; `username` — то, что сравнивается с `approvedBy`: GitLab username, GitHub login,
  Bitbucket Cloud nickname, DC slug.
- `FileChange`, `Note`, `LinePoint`, `LineRange` без изменений. `lineCode` у не-GitLab провайдеров пустой.
- Парсеры GitLab (`from(Map)`) остаются в `Models.kt`; парсеры остальных — функции в их клиентах
  (`internal`, чистые, покрыты тестами).

### Сырой diff — `core/UnifiedDiff.kt` (новый)

`UnifiedDiff.split(text): List<FileChange>` режет многофайловый diff по `diff --git`. Пути берутся из
`---`/`+++`, а для бинарных и переименований без изменений — из `rename from/to` или заголовка; префиксы `a/`, `b/`,
`src://`, `dst://` снимаются, `/dev/null` даёт новый или удалённый файл. Флаги — по `new file mode`,
`deleted file mode`, `rename from`. `diff` — текст начиная с первого `@@`, его ест существующий `DiffLineMap`.
Пути в кавычках — кавычки снимаются, escape-последовательности не раскрываются.

### `DiffLineMap`

Добавляется `fun inHunk(line: Int, onNewSide: Boolean): Boolean`. Для провайдеров без `commentsOutsideHunks`
`CommentMarkers` не показывает «+» вне ханков, а контекстное действие сообщает, что комментировать можно только
строки diff.

## Провайдеры

### GitHub

База REST: `https://api.github.com` для github.com, иначе `<url>/api/v3`; GraphQL —
`https://api.github.com/graphql` или `<url>/api/graphql`.

- **Пользователь**: `GET /user` → login, name, id.
- **Список**: `GET /repos/{o}/{r}/pulls?state=open|closed&sort=updated&direction=desc&per_page=100`. Фильтры на
  клиенте: ждут ревью — `requested_reviewers[].login`; назначены — `assignees[].login`; мои — `user.login`;
  смёрженные — `state=closed` и `merged_at != null`; поиск — подстрока в названии без учёта регистра. Число
  комментариев в списке не приходит → 0.
- **MR**: `GET /pulls/{n}` → `head.ref/sha`, `base.ref/sha`, `draft`, `body`, `html_url`, `updated_at`,
  `comments + review_comments`, `mergeable == false` → конфликты, `merged_at` → state `merged`.
  Merge base — `GET /compare/{base.sha}...{head.sha}?per_page=1` → `merge_base_commit.sha`.
- **Файлы**: `GET /pulls/{n}/files` (постранично): `status` added/removed/renamed, `previous_filename`, `patch`.
  Нет `patch` при ненулевых изменениях → `tooLarge`.
- **Треды**: GraphQL `pullRequest.reviewThreads(first:100, after:)` с полями `id isResolved isOutdated path line
  startLine originalLine originalStartLine diffSide startDiffSide resolvedBy{login}` и
  `comments(first:100){databaseId body createdAt url author{login ... on User{name}}}`. Позиция: сторона RIGHT →
  `newLine`, LEFT → `oldLine`; `startLine` → `lineRange` (`type` "new"/"old" по стороне); при `isOutdated` берутся
  `original*` и `outdated = true`. Общие комментарии — `GET /issues/{n}/comments`, каждый — отдельный
  нерезолвящийся тред.
- **Новый комментарий**: на строке — `POST /pulls/{n}/comments`
  `{body, commit_id: headSha, path, line, side, start_line?, start_side?}`; сторона RIGHT, если у точки есть
  `newLine`, иначе LEFT. Общий — `POST /issues/{n}/comments`.
- **Ответ**: в тред ревью — `POST /pulls/{n}/comments/{первый databaseId}/replies`; в общий — новый issue comment.
- **Resolve**: GraphQL `resolveReviewThread` / `unresolveReviewThread` `{threadId}`.
- **Approve**: `POST /pulls/{n}/reviews {event: APPROVE, commit_id}`. Отзыв — `PUT .../reviews/{id}/dismissals`
  с моим последним APPROVED-ревью; без прав приходит 403, и его текст показывается пользователю.
- **Кто одобрил**: `GET /pulls/{n}/reviews` — последнее не-COMMENTED состояние каждого пользователя равно APPROVED.
- **Suggestion**: блок ```` ```suggestion ```` без `-N+0`; диапазон задаётся самим комментарием.

### Bitbucket Cloud

База: `https://api.bitbucket.org/2.0`. URL подключения фиксирован — `https://bitbucket.org`. Путь проекта —
`workspace/repo`.

- **Пользователь**: `GET /user` → `nickname` (username), `display_name`.
- **Список**: `GET /repositories/{ws}/{repo}/pullrequests?state=OPEN|MERGED&sort=-updated_on&pagelen=50&q=…`;
  ждут ревью — `reviewers.nickname="me"`, мои — `author.nickname="me"`, поиск — `title ~ "…"` (кавычки и `\`
  экранируются). Число комментариев — `comment_count`.
- **MR**: `GET /pullrequests/{id}`. Хэши в ответе укороченные, поэтому полные берутся из
  `GET /commit/{hash}` для source и `GET /merge-base/{src}..{dst}` для базы; `startSha` = полный хэш destination
  (`GET /commit`). Если `source.repository.full_name` отличается от репозитория, это форк:
  `fetchUrl = https://bitbucket.org/<full_name>.git`. `fetchRef = refs/heads/<source.branch.name>`.
- **Файлы**: `GET /pullrequests/{id}/diff` (302 на `/diff/…`, text/plain, merge-base diff) → `UnifiedDiff.split`.
- **Треды**: `GET /pullrequests/{id}/comments?pagelen=100`; удалённые пропускаются, ответы собираются к корню по
  цепочке `parent.id`. `inline.to` → `newLine`, `inline.from` → `oldLine`, `start_to`/`start_from` →
  `lineRange`; `inline.outdated` (если пришёл) → `outdated`. `resolution != null` → resolved; resolvable —
  корневые комментарии. `webUrl` — `links.html.href`.
- **Новый**: `POST /comments {content:{raw}, inline?:{path, to | from, start_to | start_from}}`; `to` — если у
  точки есть `newLine`, иначе `from`.
- **Ответ**: `{content:{raw}, parent:{id}}`.
- **Resolve**: `POST` / `DELETE /comments/{id}/resolve`.
- **Approve**: `POST` / `DELETE /pullrequests/{id}/approve`.
- **Кто одобрил**: `participants[]` с `approved` → `user.nickname`.

### Bitbucket Data Center

База: `<url>/rest/api/latest`. Путь проекта из remote: ssh `PROJ/repo`, https `scm/proj/repo` (префикс `scm/`
снимается), личные — `~user/repo`. В URL → `projects/{PROJ}/repos/{repo}`.

- **Пользователь**: `GET /application-properties` → заголовок `X-AUSERNAME`, затем
  `GET /users?filter=<name>` и точное совпадение `name` → `slug` (username), `displayName`.
- **Список**: `GET /pull-requests?state=OPEN|MERGED&order=NEWEST&limit=100&start=…`; ждут ревью —
  `role.1=REVIEWER&username.1=<me>`, мои — `role.1=AUTHOR&username.1=<me>`, поиск — `filterText`.
  `updatedDate` (epoch ms) переводится в ISO. Число комментариев — `properties.commentCount`, если есть.
- **MR**: `GET /pull-requests/{id}`: `fromRef.latestCommit` → head, `toRef.latestCommit` → start, база —
  `GET /pull-requests/{id}/merge-base` → `id`. `fetchRef = refs/pull-requests/<id>/from`.
- **Файлы**: `GET /pull-requests/{id}.diff` (text/plain, от merge base) → `UnifiedDiff.split`.
- **Треды**: `GET /pull-requests/{id}/activities` (постранично), только `action=COMMENTED` с
  `commentAction=ADDED`; ответы — рекурсивно `comment.comments[]`. Якорь — `commentAnchor`: `fileType` FROM →
  `oldLine`, TO → `newLine`; `multilineMarker.startLine/startLineType` → `lineRange`; `orphaned` → `outdated`.
  resolved = `threadResolved || state == RESOLVED`.
- **Новый**: `POST /pull-requests/{id}/comments {text, anchor?}`, где
  `anchor = {diffType: EFFECTIVE, path, srcPath, line, lineType, fileType, fromHash: baseSha, toHash: headSha,
  multilineMarker?}`. Типы строк: добавленная — `ADDED/TO`, удалённая — `REMOVED/FROM`, контекст — `CONTEXT/TO`.
- **Ответ**: `{text, parent:{id}}`.
- **Resolve** (best-effort): `GET /comments/{id}` ради `version`, затем
  `PUT /comments/{id} {version, threadResolved: true|false}`.
- **Approve**: `PUT /pull-requests/{id}/participants/{mySlug} {status: APPROVED | UNAPPROVED}`.
- **Кто одобрил**: `reviewers[]` с `approved` → `user.slug`.

## Подключения

### Хранение — `MrReviewSettings`

```kotlin
class ConnectionState { var type = "GITLAB"; var url = ""; var username = "" }
// State: + var connections: MutableList<ConnectionState>, + var language = "auto", + var migrated = false
```

`Connection(type: HostingType, url: String, username: String?)` — неизменяемая обёртка для кода. Токен хранится
в PasswordSafe по URL с прежним ключом (`generateServiceName("GitLab MR Review", url)`). Пара «URL + тип»
уникальна: URL уникален.

**Миграция** (один раз, `migrated = true`). Вызывается в фоне из `MrReviewService.repositories()` и из пула при
открытии Settings. Если подключений нет, а для старого `serverUrl` (по умолчанию `https://gitlab.com`) в
PasswordSafe есть токен, добавляется подключение GitLab с этим URL. Старое поле `serverUrl` остаётся только для
миграции.

### Сопоставление репозиториев — `MrReviewService`

Для каждого репозитория берётся первый remote (origin первым), чей хост и префикс пути совпали с каким-либо
подключением (`RemoteUrl.projectPath`; для DC снимается `scm/`). Найденный `Repo` получает `connection`.
`MrSession` хранит `connection`. `client()` превращается в `client(connection)`, кэш текущего пользователя
ведётся по URL подключения.

`SetupNeeded`:
- ни один репозиторий не сопоставился — форма подключения для хоста первого remote; URL и тип угадываются;
- у подключения нет токена в PasswordSafe;
- 401 от сервера — форма для этого подключения с текстом про истёкший токен.

### UI

- **`ConnectionForm`** (выделяется из `SetupPanel`): тип (combo), URL (скрыт для Bitbucket Cloud), username (только
  для Bitbucket: «Email» для Cloud, «Username» для DC, с подсказкой «оставьте пустым для access token»), токен,
  ссылка «Создать токен», «Подключить». После успешного `currentUser()` подключение добавляется или заменяется,
  токен сохраняется. Используется в `SetupPanel` и в диалоге добавления в Settings.
- **Settings → Tools → MR Navigator**: вместо «Адрес / токен / проверить» — список подключений (тип · URL ·
  username) с кнопками + (диалог с `ConnectionForm`), − (удаляет подключение и токен) и «Проверить». Изменения
  списка применяются сразу, без Apply. Плюс combo «Язык: Авто / English / Русский» с подписью «после перезапуска
  IDE». Скрытие файлов и git остаются как есть.
- **`MrToolWindow`**: фильтры — `connection.type.filters`, номер — `prefix + iid`, переключатель репозиториев
  показывает репозитории всех подключений.
- **`MrDetailsPanel`, `ThreadPopup`, `CommentMarkers`, `MrDiffOpener`**: номер и термин из типа. Кнопка
  «Предложить изменение» — только при `canSuggest`; формат блока — `Suggestion.block(lines, gitlab: Boolean)`.
  «Открыть в браузере» в треде — `discussion.webUrl`. Тексты с «GitLab» получают имя провайдера параметром.

## Локализация

- **`MrBundle`** (`util/MrBundle.kt`): `ResourceBundle` `messages/MrBundle` (английский — базовый файл) и
  `MrBundle_ru`. Загружается с `Control.getNoFallbackControl(FORMAT_PROPERTIES)`, файлы в UTF-8. Строки
  форматируются через `MessageFormat` всегда, поэтому апострофы в `.properties` удваиваются. Короткий вызов —
  `msg(key, vararg args)`.
- **Локаль** — `@Volatile var locale`, по умолчанию из настройки: `auto` → `ru`, если `Locale.getDefault().language
  == "ru"`, иначе `en`. Без application (юнит-тесты) — `auto`. Тесты выставляют локаль явно.
- **Множественное число**: `plural(n, key)` берёт `key.one|few|many`; правило для ru — нынешнее
  `TimeAgo.plural`, для en — `one` при n == 1, иначе `many`.
- **`TimeAgo`**: строки из бандла, формат даты — в локали бандла.
- **Действие в контекстном меню**: в `plugin.xml` текст по-английски, в `AddCommentAction.update()` подставляется
  локализованный.
- **Без перевода**: `MR`/`PR`, `Approve`, `Draft`, `suggestion`, `Resolve`, имена провайдеров.
- **Переименование**: плагин называется «MR Navigator»; id, имя storage и ключ PasswordSafe не меняются.

## Тестирование

Юнит-тесты (`LogicTest` и новые файлы рядом, JUnit 4, без IDE):

- `RemoteUrl`: GitHub, Bitbucket Cloud, DC (`ssh://…:7999/PROJ/repo.git`, `https://host/scm/proj/repo.git`,
  `~user`);
- `HostingType.guess`;
- `UnifiedDiff.split`: изменённый, новый, удалённый, переименованный, бинарный файл, префиксы `src://`/`dst://`,
  несколько файлов подряд;
- `DiffLineMap.inHunk`;
- парсеры JSON каждого провайдера на примерах из официальных спек: PR → `MergeRequest`, файлы, треды → позиции
  (обычная строка, удалённая, диапазон, outdated), «кто одобрил»;
- позиция → payload нового комментария у каждого провайдера;
- `MrBundle`: множества ключей en и ru совпадают, каждый шаблон форматируется без исключений, plural для en и ru;
  `TimeAgo` в обеих локалях.

Без тестов: миграция настроек (нужны application и PasswordSafe), UI. Ручная проверка `./gradlew runIde` —
на пользователе: живых GitHub/Bitbucket у разработчика нет. Самые рискованные места — resolve и многострочные
комментарии в DC, форки в Bitbucket Cloud.

## Фазы

Каждая фаза — отдельный коммит в ветке `multi-provider`; после каждой `./gradlew test buildPlugin` зелёный.

0. Markdown в описании — сделано.
1. i18n: `MrBundle`, en + ru, настройка языка, все строки UI и ошибок через бандл.
2. Слой провайдеров и подключения: `HostingType`, `HostingClient`, `Http`, модели, `ConnectionForm`, список в
   Settings, миграция; GitLab переходит на новый слой без изменения поведения.
3. GitHub.
4. `UnifiedDiff`, Bitbucket Cloud, Bitbucket DC.
5. README (en, ru), CHANGELOG, `plugin.xml` (имя, описание), версия 0.3.0.
