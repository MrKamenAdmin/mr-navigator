# MR Navigator

[![Build](https://github.com/MrKamenAdmin/mr-navigator/actions/workflows/build.yml/badge.svg)](https://github.com/MrKamenAdmin/mr-navigator/actions/workflows/build.yml)

**English** · [Русский](README.ru.md)

A GoLand plugin for reviewing GitLab merge requests and GitHub and Bitbucket pull requests inside the IDE — with working code navigation in the diff and without generated noise like `*.pb.go`.

The built-in integrations show the diff from revisions, so Ctrl+Click, Find Usages and the rest of the code insight don't work there, and generated files bury the real changes. This plugin checks the request out and puts the real project file on the right side of the diff, hides generated files, and keeps the review workflow — comments, suggestions, approve — in the same place.

## Features

- **GitLab, GitHub and Bitbucket**: gitlab.com and self-hosted GitLab (REST v4), github.com and GitHub Enterprise Server, Bitbucket Cloud and Bitbucket Data Center. Several connections at once — each repository uses the one whose server its git remote points to.
- **Request list** for the project: all open, waiting for my review, assigned to me (not on Bitbucket), mine, merged; search by title.
- **One-click checkout.** The plugin fetches the request, stashes your uncommitted changes and switches to a local `mr/<number>` branch. **Go back** returns you to your branch and restores the stash.
- **Diff with navigation.** The right side of the diff is the real project file, so Ctrl+Click, Find Usages and the rest of the navigation work, including symbols outside the changed lines. Next/previous file walks through the whole request.
- **Generated files hidden.** Files ending with the suffixes you configure (e.g. `.pb.go`) are left out of both the file tree and the diff, and so are folders that contain only such files, at any depth. **Show hidden (N)** brings them back temporarily. Hiding is off by default — see [Settings](#settings).
- **Comments:**
  - thread icons in the diff gutter; a click opens the thread to read, reply, resolve or open in the browser;
  - **+** on the hovered line, or right click → **Comment on Line / Selection**, starts a new thread;
  - **multi-line comments**: select lines and click **+** — in the unified view a range can go from a removed line to an added one;
  - **suggestions** (GitLab, GitHub): **Suggest a change** inserts a `suggestion` block with the current code of the line(s); on GitLab, **Apply suggestion** under a comment commits it to the request branch, like the button on the web;
  - **reviews**: **Add to Review** keeps a line comment as a draft (an edit icon in the gutter); **Review (N)…** sends the drafts at once with a summary and a verdict — Comment, Approve or Request changes. GitLab, GitHub and Bitbucket Data Center send one notification for the whole review;
  - **edit and delete** your own comments; comments are rendered as GitHub-flavoured Markdown;
  - a comment typed into a popup closed without sending is offered again when the popup reopens;
  - the **Discussion** tab lists all threads, open ones first; a double click on a line thread opens the diff at that line.
- **Files tab:** +/− line counts per file, *viewed* marks (set automatically when a file is opened, Space toggles, reset by new commits).
- **Description** rendered as GitHub-flavoured Markdown: headings, tables, task lists, quotes.
- **Several repositories in one folder:** a repository switcher appears when the opened folder contains several repositories.
- **Approve / revoke approval, Request changes / withdraw the request**; the card shows who approved and who requested changes.
- **CI status** of the head commit in the card (a click opens it, the tooltip lists the checks), and **Merge…** with the strategies the server allows and an option to delete the source branch.
- Open in browser.
- **English and Russian** interface.
- The IDE's proxy and certificate settings apply.

## Requirements

- GoLand 2026.2 or newer. The plugin uses only the IntelliJ Platform, so other IntelliJ-based IDEs 2026.2+ should work too, but only GoLand is tested.
- `git` on `PATH` (or its path set in the plugin settings).
- A token for each hosting:
  - GitLab: personal access token with the `api` scope;
  - GitHub: classic personal access token with the `repo` scope;
  - Bitbucket Cloud: API token with the `read:user`, `read:repository`, `read:pullrequest`, `write:pullrequest` scopes and your Atlassian e-mail, or a repository/project/workspace access token;
  - Bitbucket Data Center: HTTP access token with the Repository write permission.

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
   - the hosting and its address are guessed from the project's git remote — correct them if needed (e.g. GitLab installed under a sub-path);
   - **Create a token →** opens the token page of that hosting (on GitLab and GitHub with the name and scope already filled in);
   - for Bitbucket Cloud also enter your Atlassian e-mail;
   - paste the token and click **Connect**. The token is kept in the IDE password storage.
3. Pick a request and click **Checkout & review**. The diff of the first file opens, and the card shows that the working copy is on the request code.
4. Optionally, set up hiding of generated files in [Settings](#settings).
5. Review: walk through files, navigate the code, comment with **+**, select lines for a multi-line comment, suggest changes.
6. **Approve**, then **Go back** to return to your branch with your changes restored.

Without checkout the diff still opens, but its right side is a revision rather than the project file, so navigation doesn't work — the diff title says so.

## Settings

**Settings → Tools → MR Navigator:**

- **connections**: add (+), remove (−) and test connections; the token of each is kept in the IDE password storage. The GitLab server and token of version 0.2 become a connection automatically;
- **hidden files**: list suffixes one per line (e.g. `.pb.go`, `.pb.gw.go`, `_mock.go`) and turn on **Hide files with these suffixes…**. Both are empty / off by default;
- path to `git`, automatic stash on checkout;
- **language**: automatic (the system language), English or Russian; applies after an IDE restart.

## How it works

| Source (`src/main/kotlin/me/brekhin/mrnavigator/`) | What it does |
|---|---|
| `api/Hosting.kt`, `api/Http.kt`, `api/Models.kt` | hosting types, the common client interface, HTTP via the IDE's `HttpRequests`, the shared model |
| `api/GitLabClient.kt`, `api/GitHubClient.kt`, `api/BitbucketCloudClient.kt`, `api/BitbucketServerClient.kt` | GitLab REST v4; GitHub REST and GraphQL (review threads); Bitbucket Cloud REST 2.0; Bitbucket Data Center REST 1.0 |
| `git/GitCli.kt`, `git/RepoScanner.kt` | git from the command line: fetch the request's head (`refs/merge-requests/<iid>/head`, `refs/pull/<n>/head`, `refs/pull-requests/<id>/from`, or the source branch on Bitbucket Cloud), stash, checkout, `git show`; finding repositories in the project folder. The Git4Idea plugin API is not used — it is internal and has been split into modules since 2025.3 |
| `core/DiffLineMap.kt` | maps lines between the old and new versions using the hunks; builds comment positions and `line_range` / `line_code` for multi-line comments, counting lines the same way as GitLab's `lib/gitlab/diff/parser.rb` |
| `core/UnifiedDiff.kt` | splits Bitbucket's raw diff into files and hunks |
| `core/Drafts.kt`, `ui/ReviewDialog.kt`, `ui/MergeDialog.kt` | drafts of a review kept between IDE restarts; submitting a review; merging |
| `core/HiddenFiles.kt`, `ui/ChangesTree.kt` | hiding files; folders are built only from shown files, so folders with nothing but hidden files never appear |
| `diff/MrDiffOpener.kt` | diff chain: base revision on the left, the local file on the right |
| `diff/MrDiffExtension.kt`, `diff/CommentMarkers.kt` | thread icons, **+**, ranges and suggestions in side-by-side, unified and one-sided diffs |
| `core/MrReviewService.kt` | per-project state: repositories and their connections, the loaded request, checkout / go back, viewed marks |
| `util/MrBundle.kt`, `messages/MrBundle*.properties` | English and Russian strings |
| `ui/*` | tool window, connection form, request card, thread popups |

## Limitations

- Outdated threads (written for an older version) are drawn in the gutter only on GitLab and GitHub, when the commit they were written on is in the local repository and their line didn't change; all of them are listed on the Discussion tab, marked as outdated.
- Bitbucket Cloud has no API for batched reviews: the comments of a review are sent one by one, with a notification each.
- Merge: no auto-merge or merge queue; Bitbucket Data Center can't delete the source branch from the plugin.
- No line comments for files whose diff the server doesn't return because of their size.
- GitHub and Bitbucket accept comments only on lines of the diff hunks; the **+** is not shown elsewhere.
- Suggestions: inserting works on GitLab and GitHub, applying only on GitLab (GitHub and Bitbucket have no API for it). Suggestions are possible only on lines of the new version. An applied suggestion is a new commit on the server: refresh the request and check it out again to get it locally.
- GitHub: an approval can't be withdrawn by its author, only dismissed — that needs write access to the repository.
- Bitbucket Data Center: resolving threads and multi-line comments rely on undocumented API fields and may not work on older servers.
- Bitbucket Cloud: a pull request from a fork is fetched over https from the fork; a private fork needs git credentials for it.
- Remotes that use an SSH host alias (`Host github-work` in `~/.ssh/config`) are not matched to a connection.
- In the side-by-side view a range stays on one side; switch to the unified view to select removed and added lines together.
- If you edit files on an `mr/<number>` branch and then check out another request in the same repository, those edits are stashed (marked `mr-review` in `git stash list`) but not restored by *Go back*; the changes stashed by the first checkout are.

## Contributing

Issues and pull requests are welcome.

```bash
./gradlew test       # unit tests of the platform-independent logic
./gradlew runIde     # sandbox GoLand with the plugin
```

Good first contributions: drafts of unsent comments, more languages (add `MrBundle_<lang>.properties`).

## License

[MIT](LICENSE) © 2026 Aleksandr Brekhin
