# Watchman

Details coming soon.

## Getting started

    lein lobos migrate
    bin/run_app.sh

## Developing

To run the tests:

    lein midje

* For UI changes, use the classic workflow: modify the source and refresh the browser.
* For non-UI changes, running code via the REPL is the fastest way to test and iterate. Sending emails and
  polling checks requires a bit of state to exercise. The `dev-sandbox` namespace has a few functions to
  quickly create some state for testing/development, and some commonly-used code for sending emails, for
  instance.

## REST API

Watchman has a RESTful HTTP API for programatically managing data.

All API routes require HTTP Basic authentication:

    curl --user username:password -X DELETE http://watchman-hostname.com/api/v1/roles/1/hosts/example.com

### Routes

Adding a host to a role:

Params:
  `hostname`: Required. The hostname to add to the role.

    POST /api/v1/roles/{id}/hosts

Example response:

    {
      "id" : 11,
      "hostname" : "example.com"
    }

Remove a host from a role:

    DELETE /api/v1/roles/{id}/hosts/{hostname}
