# `apodini`

A Clojure library for interacting with OpenStack Swift or Rackspace
CloudFiles.

The name comes from the taxonomic tribe for the typical swift,
'apodini', which includes 29 species of swift under the family
apodidae. That word comes from the ancient greek 'apous' meaning
"without feet" since swifts have very short legs.

## Installation

`apodini` is available as a Maven artifact from
[Clojars](http://clojars.org/sonian/apodini):

```clojure
[sonian/apodini "1.0.0"]
```

Apodini requires Clojure 1.4.0 or later.

## Usage

Apodini provides an API around the [cloudfiles REST
API](http://docs.rackspace.com/files/api/v1/cf-devguide/content/Overview-d1e70.html).

### Authentication

Just as Swift supports several authentication mechanisms, Apodini
provides support for different ways to authenticate, based on your
particular needs.

Require it:

```clojure 
(ns my-app.core 
  (:require [apodini.auth :as swift-auth]))
```

Then use the appropriate function. It will return a map, which is a context
that carries the appropriate storage url and token. All of the other functions
will need it.

For Swift standard v1.0 auth:

```clojure 
(def session (swift-auth/authenticate my-account my-key my-auth-url))
```

Rackspace CloudFiles also supports regions via its v2.0 auth system, and so 
does Apodini. Use the `authenticate-with-region` function, which supports the
same arguments, but also allows you to provide three extra options:

* ```:region``` -- specify the region you want
* ```:internal true``` -- retrieve the internal URL for that region (default)
* ```:external true``` -- retrieve the external (public) URL

If your CloudFiles account includes a default region, and you do not provide
a :region option, it will use that. If neither a default nor a :region exists,
Apodini will not know which region to provide you, and will throw an exception.

Example:

```clojure
(def session (swift-auth/authenticate-with-region
               my-account my-key my-auth-url
               :region "DFW"
               :external true))
```

Apodini can also list available regions, if you need to programmatically discover
them:

```clojure
(swift-auth/list-available-regions my-account my-key my-auth-url)
```

### API

Require the api namespace:

```clojure
(ns my-app.core 
  (:require [apodini.api :as swift]
            [apodini.auth :as swift-auth]))
```

The apodini.api namespace provides a helper macro, to wrap your calls
in a given authentication session, like so:

```clojure
(swift/with-swift-session 
  (swift-auth/authenticate my-account my-key my-auth-url)
  (swift/ls "my-container/"))
```

You can also cache the auth map yourself, and pass it as the first
argument to any call:

```clojure
(def session 
  (auth/authenticate my-account my-key my-auth-url)) 
(swift/ls session "my-container/")
```

#### Working with containers

```clojure 
(swift/with-swift-session
  (swift-auth/authenticate my-account my-key my-auth-url)

  ;; create a container 
  (swift/create-container "my-container")

  ;; list a container's contents returns a lazy-seq of maps of the
  ;; contents of the container (this example prints all of the
  ;; hashes, sizes and names) 
  (doseq [obj (swift/ls "my-container/")] 
    (println (:hash obj) (:bytes obj) (:name obj)))

  ;; get metadata headers (returns a map with keywordized keys)
  (swift/get-meta "my-container")

  ;; set metadata headers 
  (swift/set-meta "my-container" "X-Container-Meta-foo" "bar")

  ;; delete metadata headers 
  (swift/delete-meta "my-container" "X-Container-Meta-foo")

  ;; delete a container 
  (swift/delete-container "my-container"))
```

#### Working with objects

```clojure 
(swift/with-swift-session 
  (swift-auth/authenticate my-account my-key my-auth-url)

  ;; create an object from a file, input-stream, or byte-array note:
  ;; byte-array data will include automatic ETag header generation
  (swift/put-object "my-container/my-object" my-data)

  ;; get object bytes 
  (swift/get-object-bytes "my-container/my-object")

  ;; get object, streaming 
  (swift/get-object-stream "my-container/my-object")

  ;; ranged gets are also supported with `offset` and `length`
  ;; this gets 1k of data from 2k deep into the object
  (swift/get-object-stream "my-container/my-object" 2048 1024)

  ;; delete object 
  (swift/delete-object "my-container/my-object"))

```

### Extra clj-http options

If you need to pass extra arguments to clj-http to more carefully
control how apodini is connecting, you can add them by passing a map
for the :http-opts keyword argument to your appropriate authenticate
function. This will cause authenticate to use those options, and also
to carry them forward on the session map as :http-opts, which you can
override there on an individual per-call basis as well if you need to.

Here is an example of authenticating against an https endpoint with an
untrusted SSL cert:

```clojure
(swift-auth/authenticate my-account 
                         my-key 
                         my-ssl-auth-url 
                         :http-opts {:insecure? true})
```

## Development

The tests require you to have a Swift instance to test against (for
obvious reasons!). See [Swift
All-In-One](http://docs.openstack.org/developer/swift/development_saio.html)
for developer setup instructions. Create a `$HOME/.apodini.conf` file if
you need to override the test config with auth-url, credentials, etc.

Development of features like region support requires an instance to test
against that supports this, such as Rackspace CloudFiles itself. Certain
unit tests cannot run without those features being present. If you have
access to them, and want to see those tests running too, you can enable
them in your `.apodini.conf` test config, by adding some extra keys. See
`./test/apodini/test/config.clj` comments for the specifics.

The coding standard for this project is opinionated; all code must
pass `lein bikeshed` before merge.

## License

Apodini is released under the [Apache Licence, 2.0](https://raw.github.com/sonian/apodini/master/LICENSE.txt).
