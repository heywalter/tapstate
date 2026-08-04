# Connector seed directory (optional)

This directory is bind-mounted into the server as its connector seed directory. Drop connector
`*.jar` files here **before** starting the stack and each one is registered once, at server
startup, through the same register-if-absent path `tapstate register` uses. It is a convenience
for staging jars offline or in bulk.

This release registers **MySQL and MongoDB only**. A jar declaring any other connector is refused
here exactly as it is refused over the wire — staging it in this directory is a different way to
reach the same registration, not a way around what that registration accepts. The refusal is
reported for that jar alone and the sweep carries on with the rest.

It is **not** how a connector is normally registered, and it is **not** a precondition for
registration. The usual path is:

```
tapstate register <path-to-jar>
```

which uploads the jar's bytes to the running server over HTTP -- no mount, and nothing in this
directory, is involved. Registering that way works whether or not this directory exists or holds
anything. **Leaving this directory empty is the expected case.**

Notes:

- The mount is read-only: files here are read, never written. The registered bytes live in the
  store (Mongo), not here, so removing a jar after it has been registered does not unregister it.
- Only `*.jar` is swept; this README is ignored.
- Both routes -- a jar swept from here and a jar uploaded by `tapstate register` -- reach the
  identical content-hash register-if-absent path, and are held to the same accepted connector set,
  so the same jar registered either way is one registration, not two.
