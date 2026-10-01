# Changelog

## 0.4.1

- Merge can't be cancelled midway any more: a sent merge goes on on the server, and a cancel reported it as failed. Bitbucket Cloud no longer waits a minute for a merge that is already done.
- A Comment review with no comments and no summary is not submitted.
- A comment, reply or review that was sent is no longer reported as failed when only the refresh after it failed — retrying used to post it twice.
- GitLab: a merge status that is still being computed is not shown as a blocker; "Delete source branch" set in the merge request is preselected (also on Bitbucket Cloud); a pipeline waiting for a manual job is shown as waiting, not running.
- GitHub: the reason a pull request can't be merged is told in words — conflicts, branch protection, draft.
- GitHub and Bitbucket Cloud: the diff opens after the target branch was force-pushed.
- Broken drafts saved without a file or a line are dropped.

## 0.4.0

- Reviews: keep line comments as drafts and submit them together with a summary and a verdict — Comment, Approve or Request changes. One notification on GitLab, GitHub and Bitbucket Data Center.
- Request changes and withdraw the request; the card shows who approved and who requested changes.
- Edit and delete your own comments.
- CI status of the head commit in the card, and Merge with the strategies the server allows.
- Outdated threads (GitLab, GitHub) are drawn in the gutter where their line moved, if it didn't change.
- Comments are rendered as GitHub-flavoured Markdown.
- GitHub and Bitbucket Cloud: the merge base is computed by git — pull requests from forks get their diff on Bitbucket Cloud.
- Bitbucket: files with non-ASCII names in the diff.
- A comment typed into a popup closed without sending is offered again.

## 0.3.2

- The icon of a multi-line thread is on the first line of the commented code, not on the last.
- Installing or updating the plugin asks for an IDE restart: updating on the fly could leave the diff without comment markers.

## 0.3.1

- GitHub and Bitbucket: a multi-line comment stays within one diff hunk; a range across two hunks is refused with an explanation instead of a server error.
- Settings of 0.2 move to a GitLab connection even if the password storage was not readable at the first start.
- Bitbucket Cloud: pull requests are filtered by account uuid instead of the non-unique nickname; a reply to a thread whose first comment was deleted goes under the first remaining comment.
- GitHub: a pull request opens even when its merge base can't be read.
- Relative links in descriptions resolve against the project on every hosting.

## 0.3.0

- GitHub (github.com and Enterprise Server), Bitbucket Cloud and Bitbucket Data Center pull requests, alongside GitLab.
- Several connections at once: the hosting is picked by the host of the repository's git remote. Settings and the token of an existing GitLab connection carry over.
- English interface; the language is chosen in the settings (automatic by default — Russian on a Russian system).
- Merge request descriptions are rendered as GitHub-flavoured Markdown: headings, tables, task lists, quotes.
- The plugin is renamed to MR Navigator.

## 0.2.0

- Apply suggestions from the comment thread: GitLab commits them to the merge request branch.

## 0.1.0

First public version.

- Merge request list with filters, search and a repository switcher for folders with several repositories.
- One-click checkout into `mr/<iid>` with automatic stash and "Go back".
- Diff with the real project file on the right side — code navigation works.
- Hiding generated files by configurable suffixes (e.g. `.pb.go`; off by default) and folders that contain only them.
- Line, multi-line and general comments, replies, resolve, suggestions, approve.
- Files tab with +/− counts and viewed marks; Discussion tab that opens the diff at the commented line.
- Connection form with a pre-filled link to create a personal access token.
