# Breaking Change Log

All breaking changes to this project will be documented in this file.

The format is based on [Keep a Changelog](http://keepachangelog.com/)
and this project adheres to [Semantic Versioning](http://semver.org/).


## [v7.13]

* Account email addresses must now be unique, so that the internal identity provider's 'Forgot password'
  flow can identify an account from the email address the user gives it. An account may still have no
  email address at all, in which case it cannot reset its password by email, and any number of accounts
  may have no email address.

  **If two or more accounts currently share an email address then the database migration will stop with
  an error listing the addresses concerned, and Stroom will not start.** Before upgrading, give each
  account its own email address, or clear the email address of all but one of them, e.g.

  ```sql
  SELECT email, COUNT(1), GROUP_CONCAT(user_id)
  FROM account
  WHERE email IS NOT NULL
  GROUP BY email
  HAVING COUNT(1) > 1;
  ```

  This only affects the internal identity provider. Creating or updating an account with an email address
  that another account already uses is now rejected.

## [v7.3]
* StroomQL `vis as` keyword combination replaced with `show`.

## [v7.2]

* Quoted strings in dashboard table expressions can now be expressed with single and double quotes. As part of this change apostrophes in text are no longer escaped with `''` but instead require a leading `\` before them if they are in a single quoted string. In many cases it is preferable to use double quotes if the string in question has an apostrophe. Note that the use of `\` as an escape character also means that any existing `\` characters will need to be escaped with a preceding `\` so `\` must now become `\\`.  