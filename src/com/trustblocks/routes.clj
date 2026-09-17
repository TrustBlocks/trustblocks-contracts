(ns com.trustblocks.routes
  (:require [com.biffweb.authenticate :as biff.auth]
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
(defpath contract "/contracts/:id")
(defpath contract-document "/contracts/:id/document")
(defpath contract-model "/contracts/:id/model")
(defpath contract-model-concerto "/contracts/:id/model/concerto")
