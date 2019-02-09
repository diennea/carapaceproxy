#!/bin/bash

if [ "$#" -ne 2 ]
then
    echo "Usage: dump|validate|apply configuration.config"
    exit
fi
case "$1" in
    "dump")
        curl -X GET localhost:8001/api/config --user admin:admin -o "$2"
        ;;
    "validate")  
        curl -X POST localhost:8001/api/config/validate --user admin:admin -H "Content-Type: text/plain" --data-binary @"$2"
        ;;
    "apply")
        curl -X POST localhost:8001/api/config/apply --user admin:admin -H "Content-Type: text/plain" --data-binary @"$2"
        ;;
    *)
        echo "Usage: dump|validate|apply configuration.config"
        ;;
esac
