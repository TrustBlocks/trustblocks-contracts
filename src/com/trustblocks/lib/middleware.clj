(ns com.trustblocks.lib.middleware
  (:require [com.trustblocks.routes :as routes]))

(defn wrap-signed-in
  "Send anyone without a session to the signin page.

  Applied to the contract pages rather than to the app as a whole: these
  hold real municipal agreements, including salaries and the terms of
  people's employment, so they are not public reading even though nothing
  here writes yet.

  The error code is one com.biffweb/authenticate already has a message for,
  so the signin page explains the redirect instead of appearing for no
  visible reason. It has to be a literal query string: the extra arguments
  that com.biffweb.ring/path takes are nippy-encoded into a single npy
  parameter, which the module's page does not read."
  [handler]
  (fn [{:keys [session] :as ctx}]
    (if (some? (:uid session))
      (handler ctx)
      {:status  303
       :headers {"location" (str (routes/signin) "?error=not-signed-in")}})))
