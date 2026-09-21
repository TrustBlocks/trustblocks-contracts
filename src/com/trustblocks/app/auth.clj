(ns com.trustblocks.app.auth
  "Passwordless email signin, on top of com.biffweb/authenticate.

  The module owns the signin pages, the one-time codes mailed out, their
  expiry and attempt limits, and the session cookie. What it needs from us
  is where users live: how to find one by email, and how to make one. Those
  two functions are this namespace, plus a third that delivers the code.

  Users are plain XTDB documents, not Concerto instances, and deliberately
  so. Everything in the :contract table is a contract -- validated against a
  model, versioned in valid time, part of the record. A user account is how
  someone reaches the application; it is infrastructure, not a term of any
  agreement, and putting it through com.trustblocks.xtdb.store would mean
  inventing a Concerto model for it and mixing the two kinds of thing in one
  store."
  (:require [com.biffweb.authenticate :as biff.auth]
            [com.trustblocks.routes :as routes]
            [xtdb.api :as xt])
  (:import [java.time Instant]))

(def user-table :user)

(defn get-user-id
  "The id of the user with this email, or nil."
  [{:keys [biff.xtdb/node]} email]
  ;; The email has to be a literal in the binding pattern. Passing it as an
  ;; :args parameter does not filter -- XTDB reads $email as another column
  ;; to project and hands back the whole table. Same trap as
  ;; com.trustblocks.xtdb.store/get-instance, and projected columns are
  ;; symbols rather than keywords for the same reason as model-source.
  (:xt/id (first (xt/q node (list 'from user-table
                                  [{:user/email email} (symbol "xt/id")])))))

(defn user-email
  "The email of the user with this id, or nil.

  The inverse of get-user-id, and used where an execution records who
  submitted it (com.trustblocks.app.trigger). The email rather than the id
  goes into that record on purpose: an audit row should say what identified
  the submitter at the time, not depend on a join against an account that
  may later be renamed or deleted."
  [{:keys [biff.xtdb/node]} user-id]
  (when user-id
    (:user/email (first (xt/q node (list 'from user-table
                                         [{:xt/id user-id}
                                          (symbol "user/email")]))))))

(defn create-user!
  "Create a user for this email and return its id."
  [{:keys [biff.xtdb/node]} {:keys [email]}]
  (let [id (random-uuid)]
    (xt/execute-tx node [[:put-docs user-table
                          {:xt/id          id
                           :user/email     email
                           :user/joined-at (Instant/now)}]])
    id))

(defn send-email
  "Prints the signin code instead of sending it.

  There is no mail provider configured, and rather than fail a signin that
  would otherwise work, this puts the code on the console so local signin is
  usable. It is loud about it: a deployment that reaches real users needs a
  real provider here, and silently 'sending' mail that goes nowhere is the
  kind of thing that is discovered late.

  Returning a falsy value would send the visitor back to the signin page
  with a send-failed error, so this returns true."
  [_ctx {:keys [to subject code]}]
  (println)
  (println "=== SIGNIN EMAIL (not sent -- no mail provider configured) ===")
  (println "  to:      " to)
  (println "  subject: " subject)
  (println "  code:    " code)
  (println "==============================================================")
  (println)
  true)

(def module
  (biff.auth/module
   {:biff.auth/app-name    "Trustblocks"
    ;; Where the module sends someone once the code checks out, and where it
    ;; sends them to get one.
    :biff.auth/app-path    (routes/contracts)
    :biff.auth/signin-page (routes/signin)
    :biff.auth/get-user-id #'get-user-id
    ;; No trailing bang on the option key: the module names it create-user.
    :biff.auth/create-user #'create-user!
    :biff.auth/send-email  #'send-email
    ;; The module refuses to start with captcha unconfigured unless this is
    ;; set, which is the right default -- an open signin form is a spam
    ;; relay. Turned off here because there is no deployment yet and no
    ;; provider keys; turning it back on is configuring hcaptcha/turnstile
    ;; and deleting this line.
    :biff.auth/skip-captcha true
    ;; Not a reduction in protection: biff.ring's wrap-csrf-protection
    ;; already guards every route in the app, this one included, by
    ;; checking Sec-Fetch-Site and Origin against Host, and it rejects
    ;; requests that carry neither header. What this turns off is the
    ;; second, token-based layer the module puts on its own three routes.
    ;;
    ;; That layer can't stay, because signout is one of those routes and
    ;; the form that posts to it is ours (see com.trustblocks.app.landing).
    ;; The token only exists inside the module's middleware, so a form
    ;; rendered anywhere else submits without one and gets a 403 -- which
    ;; is exactly what happened. The choice is between a token check on
    ;; three routes and a header check on all of them; one rule for the
    ;; whole app is the better one.
    :biff.auth/skip-csrf-protection true}))
