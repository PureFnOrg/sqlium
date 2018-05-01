# sqlium

A Datomic-inspired Clojure library for entity extraction (Big E in ETL) from a
relational database.

## Rationale

### The Rectangular Prison

Give credit where credit is due: the relational algebra is pretty
great. It's solid, relatively easy to reason about, and we've put 50
years and billions of dollars of engineering effort into making good
relational database implementations.

The relational model's great strength is in its unifying abstraction,
the relation. Basically a table with named columns. This is also its
great weakness: everything is a table. Your data is a table, and the
answer to any question you ask the database will be a table; a
rectangular prison that you cannot escape.

The pervasive rectangleness of relational data is somewhat
inconvenient for us as Clojure developers, since most interesting data
that we work with is tree-shaped, not rectangle-shaped. It's so easy
and natural for us to create and work with nested data structures, and
they are often the most natural representation of the data models we
work with.

For example, we'll say we have a database of music recordings, and
we're interested in albums. An album has one or more album artists -
which could be bands that have one or more members - and one or more
tracks each of which has properties including one or more artists,
possibly one or more songwriters, possibly one or more producers, etc.

The natural representation of this kind of data for us is something
like:

``` clojure
{:name "Abbey Road"
 :artist [{:name "The Beatles"}]
 :tracks [{:name "Come Together"
           :number 1
           :artist [{:name "The Beatles"}]
           :songwriter [{:name "John Lennon"}
                        {:name "Paul McCartney"}]
           :producer [{:name "George Martin"}]}
          {:name "Something"
           :number 2
           :artist [{:name "The Beatles"}]
           :songwriter [{:name "George Harrison"}]
           ...}
          ... etc
          ]}
```

But me tell you what, it sure is annoying to get to this point if
you're starting from a relational database. Because, again, it's the
rectangles. Whenever you're dealing with nested zero-or-more things,
you can't get it all at once. You have to do a bunch of queries to get
little rectangular pieces and then stitch them together into a tree
yourself.

A typical way to model this in a relational database might have the
following tables:

- albums
- artists
- album_artists
- tracks
- album_tracks
- track_artists
- track_songwriters
- track_producers

To get "Abbey Road", we'd need to do the following queries:

1. query the albums table
2. query the album_artists table to get our list of album artists
3. query the album_tracks table joined with the tracks table to get
   the tracks
4. query the track_artists table with all the track ids
5. query the track_songwriters table with all the track ids
6. query the track_producers table with all the track ids
7. collect all the artist ids from the above results and get them all
   from the artists table

After doing all these queries, we need take the data we get back and
put it together in our application with code that understands each
relationhsip and how it goes into the output.

While it's true that there are "ORM" tools that let you do this
without writing all the code yourself, let's just say it's a matter of
some controversy whether they deliver on their promises or
meaningfully improve the experience of building systems.

### Ideas From DJ Decomplexion

Fortunately we have our own approach to databases called Datomic,
designed by our very own DJ Decomplexion Rich Hickey. Datomic's
approach to entities is much closer to how we treat them in our
application code, and it includes a great little interface called
"Pull API" for, well, pulling them out.

In Pull syntax, we can grab our entire album entity with one call,
using a declarative system for describing the entity and its nested
pieces. It might look something like this:

``` clojure
[:album/name
 {:album/artist [:artist/name]}
 {:album/track
  [:track/name :track/number
   {:track/artist [:artist/name]}
   {:track/songwriter [:artist/name]}
   {:track/producer [:artist/name]}]}]
```

It's fantastically shorter, simpler, clearer, more direct. What if we
could do something like with in a relational database?

sqlium provides a DSL to do just that.

## sqlium DSL

In sqlium, the equivalent to the above Pull syntax example to get the
contents of an album might look like this:

``` clojure
(Table albums
       :id "album_id"
       :fields "name"
       {["_album_id" :as "artists"]
        (Table album_artists
               :id "album_artist_id"
               :fields
               {["artist_id" :flatten]
                (Table artists
                       :id "artist_id"
                       :fields "name")})}
       {["_album_id" :as "tracks"
         (Table album_tracks
                :id "album_track_id"
                :fields
                {["track_id" :flatten]
                 (Table tracks
                        :id "track_id"
                        :fields "name" "number"
                        {["_track_id" :as "artists"]
                         (Table track_artists
                                :id "track_artist_id"
                                :fields
                                {["artist_id" :flatten]
                                 (Table artists
                                        :id "artist_id"
                                        :fields "name")})}
                        {["_track_id" :as "songwriters"]
                         (Table track_songwriters
                                :id "track_songwriter_id"
                                :fields
                                {["artist_id" :flatten]
                                 (Table artists
                                        :id "artist_id"
                                        :fields "name")})}
                        {["_track_id" :as "producers"]
                         (Table track_producers
                                :id "track_producer_id"
                                :fields
                                {["artist_id" :flatten]
                                 (Table artists
                                        :id "artist_id"
                                        :fields "name")})})})]})
```

Here's a piece-by-piece explanation of the syntax.

### Table spec

This is the basic building block of sqlium's DSL. It describes some
basic information about a databse table, the data that we're
interested in from the table, and relationships with other tables that
we are interested in.

```clojure
(Table name id-spec? :fields data-spec+)
```

The table's name is a symbol, which should match the name of the table
in the database.

sqlium uses the idea of identity (aka primary key) columns throughout,
specified in the id-spec. This is optional because it has a default
value of `<table_name>_id`. If your database uses a different naming
convention or otherwise has custom names for identiy columns, then you
need to provide it for each table.

An id-spec looks like: `:id "id-column-name"`.

### Data specs

A data spec is either a field or a relationship.

#### Fields

Fields describe the data columns from the table that you're interested
in. They come in two forms. Simple field specs are just strings that
match the name of the column in the table. No transformations will be
applied to the column name or the data for simple field specs - the
name will be keywordified in the output map but otherwise will be
returned verbatim.

You can also specify several options in a complex field spec. It looks like:

```clojure
[col-name alias? transform?]
```

Aliasing sets the key name in the output map for a given field. Alias
is specified with an :as keyword and a string alias name. For example,
`["oldname" :as "newname"]` will add a `:newname` key to the output
map with the contents of the "oldname" column. Nested aliases are also
supported using a "." separator. For example, `["oldname" :as
"new.name"]` will nest the value of the "oldname" column as in
`(assoc-in output-map [:new :name] oldname-val)`.

sqlium supports a number of simple data transforms. A transform can be
either a function literal or reference to a symbol that's in scope for
the `org.purefn.sqlium.transform` namespace. This namespace includes a
built-in transform called `binary-string` which handles UTF-8 strings
encoded as BLOB/binary in the database. The clojure.set namespace is
also available as `set`, and the clojure.string namespace is available
as `str`.

Using code from your own namespaces in sqlium transforms is not
supported at this time. Generally sqlium transforms are meant to be
for quite simple typecasts, filtering, or replacement operations, and
for more involved transformations you should generally perform them on
the returned entity.

nil values will not be added to the output.

#### Relationships

A relationship is how nested tables are added to an entity - aka a
"join".

sqlium understands two types of relationships: one-to-one, and one-to-many.

A one-to-one relationship is one in which the some column in the
parent table points to the id column of the child table. Since sqlium
assumes that id columns are unique, there will only be one row in the
child table for the relationship. If an id column in a one-to-one
relatinship is not unique, you'll get a cartesian product and multiple
entities will be returned.

A one-to-many relationship is one in which some column in the child
table points to the id column of the parent table. These will always
be returned as collections.

Relationships are specified as maps with one key, in this form:

```clojure
{join-spec table-spec}
```

A `join-spec` is has two different forms, for one-to-one and
one-to-many joins.

One-to-one:

```clojure
[foreign-key flatten-or-alias?]
```

The `foreign-key` for a one-to-one relationship is the name of the
column in the parent table. One-to-one relationships can also be
flattened with a `:flatten` keyword, which means that the data from
the child table will be merged up into the parent table. Otherwise,
you can specify the key that data from the relationship will be
returned in with an `:as` keyword, just like in a field spec. If no
alias is specified, the data will be returned in the keyword form of
the foreign key name.

One-to-many:

```clojure
[foreign-key alias?]
```

In a one-to-many relationship, the foreign key is prefixed by an
underscore, denoting that it's a column in the child table. This was
borrowed from Datomic's syntax for specifying reverse references,
which is the best defense I can offer against people who don't know
Datomic and don't understand why an underscore would be used for this
purpose.

Optionally, an alias can be supplied. You probably want to supply an
alias in almost every case, because otherwise the data will be
returned in a `_{parent-id}` key which is pretty strange looking.

## Usage

The two main API functions are in the org.purefn.sqlium namespace.

```clj
(require '[org.purefn.sqlium :as sqlium])
```

You can retrieve a single entity with the `entity` function. This is
particularly useful for development and occasional production use -
although it's not lightning fast. (Performance improvements coming!)

```clj
;; simple album spec with the album name and artist names
(def album-spec
  ;; specs are quoted
  '(Table albums
          :id "album_id"
          :fields "name"
          {["_album_id" :as "artists"]
           (Table album_artists
                  :id "album_artist_id"
                  :fields
                  {["artist_id" :flatten]
                   (Table artists
                          :id "artist_id"
                          :fields "name")})}))

;; sqlium takes anything that clojure.java.jdbc can use as a database
(def db
  {:dbtype "mysql"
   :dbname "music"
   :user "sqlium"
   :password "sqlium"})

;; assuming database id of "Abbey Road" is 12
(sqlium/entity db album-spec 12)
;; =>
;; {:name "Abbey Road"
;;  :artists [{:name "John"}
;;            {:name "Paul"}
;;            {:name "George"}
;;            {:name "Ringo"}]}
```

The main use case for sqlium is extracting a lot of entities, done
through the `records` function. It takes a database, spec, and some
options to control what entities get returned. There are three
different ways to do this.

1. Entity age
2. Entities with updates
3. A table with rows pointing to records to get updated.

Here's what the docstring says about it:

```clj
(defn records
  "Returns a lazy sequence of records for spec, querying from jdbc
   datasource db. Takes optional parameters as kwargs or a map to
   either control expiry, return updated data since a given date by
   comparing against provided date time fields, or return updated data
   based on a specific update table. Only the highest-precedence
   option present will be used. In order of precedence:

   :update - a map with:
     * :table    string name of the update table
     * :id       string name of the column containing entity ids to update
     * :updated  string name of the field containing the entity update time
     * :date     anything that can be coerced to a DateTime; the records
                 returned will be newer than this date

   :delta - a map with:
     * :fields   collection of :table/column datetime fields which will
                 be compared with :date to detect updated data
     * :date     anything that can be coerced to a DateTime; the records
                 returned will be newer than this date

   :expiry - a map with:
     * :field    :table/column keyword for the datetime field that
                 determines the age of the entity
     * :age      maximum age before the entity is ignored, either as an
                 integer number of days or an expiration date as something
                 that can be coerced to a DateTime"
  [db spec & options]
  )
```

## License

Copyright © 2018 Ladders, PureFn

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
