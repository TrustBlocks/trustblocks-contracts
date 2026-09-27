(ns com.trustblocks.routes
  "Every URL this app answers to, in one place, plus the one function that
  reads a path back apart."
  (:require [clojure.string :as str]
            [com.biffweb.authenticate :as biff.auth]
            [com.biffweb.ring :refer [defpath]]))

(defpath home "/")
;; The signin page is served by com.biffweb/authenticate, but the path is
;; ours: com.trustblocks.app.auth passes it back to the module as
;; :biff.auth/signin-page, so this declaration is the one definition.
(defpath signin "/signin")
;; Signout is a POST handler wholly inside the module, at a path the module
;; picks. Take it from the library rather than repeating the literal, which
;; is how the first attempt at this ended up pointing at nothing.
(def signout (constantly biff.auth/signout-path))
(defpath contracts "/contracts")
(defpath clauses "/clauses")
(defpath authorizations "/authorizations")
(defpath contract "/contracts/:id")
(defpath contract-document "/contracts/:id/document")
(defpath contract-model "/contracts/:id/model")
(defpath contract-model-concerto "/contracts/:id/model/concerto")
(defpath contract-model-malli "/contracts/:id/model/malli")
(defpath contract-trigger "/contracts/:id/trigger")
(defpath contract-certify "/contracts/:id/certify")
(defpath contract-notes "/contracts/:id/notes")
(defpath templates "/templates")
(defpath template-text "/templates/:name/text")
(defpath template-model "/templates/:name/model")
(defpath template-code "/templates/:name/code")
(defpath matters "/matters")
(defpath matter "/matters/:id")
(defpath stored-document "/documents/:id")

(defn- path-segment
  "The first path segment after `prefix` -- read from :uri, for the reason
  contract-id gives."
  [ctx prefix]
  (-> (:uri ctx)
      (str/replace-first prefix "")
      (str/split #"/")
      first))

(defn matter-id [ctx] (path-segment ctx #"^/matters/"))
(defn template-name [ctx] (path-segment ctx #"^/templates/"))
(defn stored-document-id [ctx] (path-segment ctx #"^/documents/"))

(defn contract-id
  "The :id out of any /contracts/:id/... request -- the inverse of the paths
  above, which is why it lives beside them.

  Read from the raw request URI instead of :path-params. biff.ring's default
  middleware stack includes wrap-path-param-uuids, which runs every
  :path-params value through a UUID-decode attempt: strings of exactly 22
  characters get base64-url-decoded, and if that happens to produce exactly
  16 bytes, the decoded bytes are reinterpreted as a UUID and silently
  replace the original string. 'china-grove-gover-2025' is exactly 22
  characters of URL-safe-base64 alphabet and happens to decode to 16 bytes,
  so it gets corrupted into an unrelated UUID before a handler ever sees it
  -- while 'snow-2026-014' (13 chars) does not. This isn't specific to those
  two ids; any future contract id landing on that 22-char/16-byte
  coincidence would misfire the same way. Reading from :uri sidesteps that
  middleware entirely, since it only touches :path-params."
  [ctx]
  (-> (:uri ctx)
      (str/replace-first #"^/contracts/" "")
      (str/split #"/")
      first))
