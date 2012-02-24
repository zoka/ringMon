# noirMon

A simple single page website written for Noir clojure  webframework. It demonstrates how to
add monitoring admin page that displays raw JMX data of interest. The page also
displays one derived value such as CpuLoad that is calculated by sampling OperatingSystem.ProcessCpuTime every
2 seconds and AJAX requests statistics. 
Monitoring data in JSON form is fetched from Noir server app either periodically or at user request.
It is also possible to force JVM garbage collection. Rapid pressing on "Force JVM GC" button will cause
considerable server side CPU load.

Adding this page to existing Noir application should be easy, just add monitor.clj to your views, monview.html
to resources/public/admin and adjust routes and namespaces accordingly. The monview.html page is self-contained. 
It depends only on jQuery and gets it from Google CDN. 

Note that for real life application such a page should be protected by admin access password, since it can
be used to inflict some serious DOS damage to your server.

The code is ready made to be deployed on Heroku - you can see it see it in action 
[here](http://noirmon.herokuapp.com/).

## Usage

```bash
lein deps
lein run
```



## License

Copyright (C) 2012 Zoran Tomicic

Distributed under the Eclipse Public License, the same as Clojure.

