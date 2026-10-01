# MR Navigator for GitLab

[![Build](https://github.com/MrKamenAdmin/mr-navigator/actions/workflows/build.yml/badge.svg)](https://github.com/MrKamenAdmin/mr-navigator/actions/workflows/build.yml)

**English** · [Русский](README.ru.md)

A GoLand plugin for reviewing GitLab merge requests inside the IDE — with working code navigation in the diff and without generated noise like `*.pb.go`.

The built-in GitLab integration shows the merge request diff from revisions, so Ctrl+Click, Find Usages and the rest of the code insight don't work there, and generated files bury the real changes. This plugin checks the merge request out and puts the real project file on the right side of the diff, hides generated files, and keeps the review workflow — comments, suggestions, approve — in the same place.

> The user interface is currently in Russian; below, UI labels are given in Russian with a translation. English UI is a welcome contribution — see [Contributing](#contributing).

## Features

- **Merge request list** for the project: all open, waiting for my review, assigned to me, mine, merged; search by title.
- **One-click checkout.** The plugin fetches the merge request, stashes your uncommitted changes and switches to a local `mr/<iid>` branch. **«Вернуться»** (*Go back*) returns you to your branch and restores the stash.
- **Diff with navigation.** The right side of the diff is the real project file, so Ctrl+Click, Find Usages and the rest of the navigation work, including symbols outside the changed lines. Next/previous file walks through the whole merge request.
- **Generated files hidden.** Files ending with the suffixes you configure (e.g. `.pb.go`) are left out of both the file tree and the diff, and so are folders that contain only such files, at any depth. **«Показать скрытые (N)»** (*Show hidden*) brings them back temporarily. Hiding is off by default — see [Settings](#settings).
- **Comments:**
  - thread icons in the diff gutter; a click opens the thread to read, reply, resolve or open in the browser;
  - **+** on the hovered line, or right click → **«Комментарий к строке / выделению (GitLab MR)»** (*Comment on line / selection*), starts a new thread;
  - **multi-line comments**: select lines and click **+** — in the unified view a range can go from a removed line to an added one, just like on GitLab;
  - **suggestions**: **«Предложить изменение»** (*Suggest a change*) inserts a `suggestion` block with the current code of the line(s); **«Применить suggestion»** (*Apply suggestion*) under a comment commits it to the merge request branch through GitLab, like the button on the web;
  - the **«Обсуждение»** (*Discussion*) tab lists all threads, open ones first; a double click on a line thread opens the diff at that line.
- **Files tab:** +/− line counts per file, *viewed* marks (set automatically when a file is opened, Space toggles, reset by new commits).
- **Several repositories in one folder:** a repository switcher appears when the opened folder contains several GitLab repositories.
- **Approve / revoke approval**, merge request description, open in browser.
- **Self-hosted GitLab** and gitlab.com, REST API v4. The IDE's proxy and certificate settings apply.

## Requirements

- GoLand 2026.2 or newer. The plugin uses only the IntelliJ Platform, so other IntelliJ-based IDEs 2026.2+ should work too, but only GoLand is tested.
- `git` on `PATH` (or its path set in the plugin settings).
- A GitLab personal access token with the `api` scope.

## Installation

The plugin is not on JetBrains Marketplace yet.

**From a release.** Download `mr-navigator-<version>.zip` from [Releases](https://github.com/MrKamenAdmin/mr-navigator/releases), then in the IDE: **Settings → Plugins → ⚙ → Install Plugin from Disk…** and choose the zip. Don't unpack it.

**From source.** You need JDK 17+ to run Gradle; the JDK 25 toolchain required by IntelliJ Platform 2026.2 is downloaded automatically.

```bash
git clone https://github.com/MrKamenAdmin/mr-navigator.git
cd mr-navigator
./gradlew buildPlugin
```

Then install `build/distributions/mr-navigator-<version>.zip` the same way.

To try it in a sandbox IDE without touching your own GoLand:

```bash
./gradlew runIde
```

If Gradle cannot resolve GoLand `2026.2`, put your exact version from **Help → About** into `gradle.properties`, e.g. `platformVersion = 2026.2.1`.

## Getting started

1. Open the **MR Navigator** tool window (left sidebar).
2. The first time, a connection form appears:
   - the GitLab address is suggested from the project's git remote — correct it if GitLab is installed under a sub-path;
   - **«Создать токен в GitLab →»** (*Create a token*) opens the token page with the name and the `api` scope already filled in;
   - paste the token and click **«Подключить»** (*Connect*). The token is kept in the IDE password storage.
3. Pick a merge request and click **«Checkout и ревью»** (*Checkout and review*). The diff of the first file opens, and the card shows that the working copy is on the merge request code.
4. Optionally, set up hiding of generated files in [Settings](#settings).
5. Review: walk through files, navigate the code, comment with **+**, select lines for a multi-line comment, suggest changes.
6. **Approve**, then **«Вернуться»** (*Go back*) to return to your branch with your changes restored.

Without checkout the diff still opens, but its right side is a revision rather than the project file, so navigation doesn't work — the diff title says so.

## Settings

**Settings → Tools → MR Navigator:**

- GitLab address and token;
- **hidden files**: list suffixes one per line (e.g. `.pb.go`, `.pb.gw.go`, `_mock.go`) and turn on **«Скрывать файлы с этими суффиксами…»** (*Hide files with these suffixes*). Both are empty / off by default;
- path to `git`, automatic stash on checkout.

## How it works

| Source (`src/main/kotlin/me/brekhin/mrnavigator/`) | What it does |
|---|---|
| `api/GitLabClient.kt`, `api/Models.kt` | GitLab REST API v4 (`/merge_requests`, `/diffs`, `/discussions`, approvals) via the IDE's `HttpRequests` |
| `git/GitCli.kt`, `git/RepoScanner.kt` | git from the command line: fetch `refs/merge-requests/<iid>/head`, stash, checkout, `git show`; finding repositories in the project folder. The Git4Idea plugin API is not used — it is internal and has been split into modules since 2025.3 |
| `core/DiffLineMap.kt` | maps lines between the old and new versions using the hunks; builds comment positions and `line_range` / `line_code` for multi-line comments, counting lines the same way as GitLab's `lib/gitlab/diff/parser.rb` |
| `core/HiddenFiles.kt`, `ui/ChangesTree.kt` | hiding files; folders are built only from shown files, so folders with nothing but hidden files never appear |
| `diff/MrDiffOpener.kt` | diff chain: base revision on the left, the local file on the right |
| `diff/MrDiffExtension.kt`, `diff/CommentMarkers.kt` | thread icons, **+**, ranges and suggestions in side-by-side, unified and one-sided diffs |
| `core/MrReviewService.kt` | per-project state: repositories, the loaded merge request, checkout / go back, viewed marks |
| `ui/*` | tool window, setup form, merge request card, thread popups |

## Limitations

- Threads that became outdated after new commits are not drawn in the gutter; they are listed on the Discussion tab, marked as outdated.
- No line comments for files whose diff GitLab doesn't return because of their size.
- Suggestions are possible only on lines of the new version, as on GitLab. An applied suggestion is a new commit on GitLab: refresh the merge request and check it out again to get it locally.
- In the side-by-side view a range stays on one side; switch to the unified view to select removed and added lines together.
- If you edit files on an `mr/<iid>` branch and then check out another merge request in the same repository, those edits are stashed (marked `mr-review` in `git stash list`) but not restored by *Go back*; the changes stashed by the first checkout are.

## Contributing

Issues and pull requests are welcome.

```bash
./gradlew test       # unit tests of the platform-independent logic
./gradlew runIde     # sandbox GoLand with the plugin
```

Good first contributions: English UI (moving strings to a resource bundle), drafts of unsent comments.

## License

[MIT](LICENSE) © 2026 Aleksandr Brekhin
