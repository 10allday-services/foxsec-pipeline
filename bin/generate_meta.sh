#!/bin/bash

# Convert existing Java alert metadata into Golang constants
#
# Called by maven and should not be run directly

f=`mktemp`
outpath=contrib/common/alertmeta.go

java -cp target/classes com.mozilla.secops.alert.AlertMeta "${f}"
gofmt $f > $outpath
