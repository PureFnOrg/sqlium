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





## License

Copyright © 2017 Ladders

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
