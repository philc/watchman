#!/bin/bash

# environment.sh is present after a production deploy.
if [ -f environment.sh ]; then
  source environment.sh
fi

if [ "$RING_ENV" == "production" ]; then
  java -Xmx1g -Xms1g -server -jar target/watchman-0.1.0-SNAPSHOT-standalone.jar
else
  lein ring server-headless
fi
