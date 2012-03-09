# ringMon

Preliminary - not ready yet.

Ring middleware that injects single monitoring page into any Clojure web application
based on Ring or higher level libraries use Ring themselves.
The page displays raw JMX data of interest in tree alike structure. It also shows
derived values such as CpuLoad that is calculated by sampling JMX/OperatingSystem.ProcessCpuTime every
2 secondsand and Ajax requests statistics.
Monitoring data in JSON form is fetched from Noir server app either periodically or at user request.
It is also possible to force JVM garbage collection.

Moreover, the page provides full featured nREPL front end with synax colored editor, command history and persistent sessions.

Adding this page to existing Ring based application should be easy.

Note that for real life application such a page should be protected by admin access password, since it can
be used to inflict some serious DOS damage to your server.

## Usage (for local test)

```bash
lein deps
lein run
```


## License

Copyright (C) 2012 Zoran Tomicic

Distributed under the Eclipse Public License, the same as Clojure.

