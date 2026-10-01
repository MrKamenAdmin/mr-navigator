# Changelog

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
