tg-eventstore
=============

A Java library for working with event-sourcing.


Modules
=======

 - api          - defines core classes for Events and interfaces for reading/writing
 - mysql        - an eventstore implementation backed by a MySQL table
 - memory       - an eventstore implementation run entirely in memory
 - get-http     - an eventstore implementation backed by Grey Young's eventstore (see https://geteventstore.com)
 - subscription - facilitates reading all the events from an eventstore and then consuming new events as they arrive
 - stitching    - utilities for merging, stitching, shovelling and otherwise manipulating event streams


Usage
=====
```java
 java.sql.Datasource db = //your db source;
 String tableName = "Event";
 new BasicMysqlEventStoreSetup(dataSource::getConnection, tableName).lazyCreate();
 
 EventSource eventstore = new BasicMysqlEventSource(dataSource::getConnection, tableName);
 
 eventstore.writeStream().write(
     StreamId.streamId("pets", "dog-412"),
     NewEvent.newEvent("AnimalBorn", "{\"dob\":\"2017-03-06\"}".getBytes(UTF8), new byte[0])
 );
 
 Stream<ResolvedEvent> eventStream = eventstore.readAll().readAllForwards();
```

Legacy
======

There are several legacy classes which support older versions of this eventstore API. For new projects, avoid
using deprecated classes.