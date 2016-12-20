Reproducer for JDK SIGBUS bug. Follow these steps:
1. Install the latest maven from https://maven.apache.org/download.cgi
2. Ensure you have curl installed (via homebrew or other means)
3. Run `mvn clean install`
4. Run `java -jar target/macos-sigbus-loop-1.0-SNAPSHOT.jar`
5. Run `while curl -s -v http://localhost:9999/js/d3.js 1> /dev/null; do echo "done"; done` from multiple prompts concurrently.
6. Wait about 5 mintues. The output from curl will stop scrolling because the JVM is in a live lock.

This is just one way to generate a SIGBUS, but I would assume if the JVM application registers for SIGBUS via sun.misc.Signal and macOS throws a SIGBUS there will a similar issue.
