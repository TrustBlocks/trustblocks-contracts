(ns com.trustblocks.lib.middleware
  (:require [com.trustblocks.routes :as routes]))

(def ^:private safe-methods
  #{:get :head :options "GET" "HEAD" "OPTIONS"})

(defn wrap-signed-in
  "Require a session.

  Applied to the contract routes rather than to the app as a whole: these
  hold real municipal agreements, including salaries and the terms of
  people's employment, so they are neither public reading nor open to
  execution by a stranger.

  Someone browsing is redirected to the signin page. Anything else gets a
  bare 401, because redirecting a POST to a signin page throws away its body
  and hands back HTML the caller cannot act on -- and a trigger's body is
  the request the contract was supposed to run. Same rule, two honest
  answers to 'who are you'.

  The error code on the redirect is one com.biffweb/authenticate already has
  a message for, so the signin page explains itself instead of appearing for
  no visible reason. It has to be a literal query string: the extra
  arguments com.biffweb.ring/path takes are nippy-encoded into a single npy
  parameter, which the module's page does not read."
  [handler]
  (fn [{:keys [session request-method] :as ctx}]
    (cond
      (some? (:uid session))
      (handler ctx)

      (safe-methods request-method)
      {:status  303
       :headers {"location" (str (routes/signin) "?error=not-signed-in")}}

      :else
      {:status  401
       :headers {"content-type" "application/json"}
       :body    (str "{\"error\":\"not-signed-in\",\"message\":"
                     "\"Sign in at " (routes/signin) " and send the session "
                     "cookie with this request.\"}")})))
