(ns seed
  "Starts the app for real (config -> xtdb -> ring -> datastar -> graph, same
  modules bin/clj -M:run dev boots, just not through the dev task's file
  watcher) and loads our two real contracts -- the snow-removal plowing
  contract and the China Grove manager employment contract -- into its
  actual XTDB storage, so /contracts has real data to show instead of an
  empty list.

  Also builds CSS once (com.biffweb.tasks/css) before starting. Without
  this, target/resources/public/css/main.css is whatever it was last time
  something ran the dev task's Tailwind watcher -- stale, missing any
  utility class introduced since, and served without complaint (a stylesheet
  missing a rule doesn't 404, the page just silently renders wrong). Found
  this the hard way: a fix landed, curl confirmed the new classes on the
  markup, and the page still looked broken because the CSS backing those
  classes had never been (re)compiled.

  Loading the model/instance/clause (each namespace's load!) always runs,
  even against storage/ left over from an earlier run -- it's a plain
  upsert of whatever's on disk, so a changed archive (a new grammar.tem.md,
  say) still takes effect on an already-seeded contract without a full
  rm -rf storage. Only the actual trigger events are guarded against
  redoing: the Board can't approve the same contract twice, and
  re-reporting the same snow visit would silently double-count the
  payout, so each is only triggered if its state/history isn't already
  there. Run `rm -rf storage` first if you want a genuinely clean slate.

  late-delivery.cta is deliberately not seeded here: it's Accord's own
  reference example, kept as an engineering proof (dev/late_delivery_demo.clj
  cross-checks our Clojure clause runtime against a known-good APAP answer),
  not one of our contracts.

  Requiring the two demo namespaces below also reruns their own standalone
  verification, each against its own fresh throwaway in-memory node -- a
  free regression check, not noise -- before this seeds the real app's node
  with the same data.

    bin/clj -M:xtdb-jvm-opts -i dev/seed.clj

  Leaves the app running afterward. Visit http://localhost:8080/contracts."
  (:require [com.biffweb.tasks :as tasks]
            [com.trustblocks :as main]
            [com.trustblocks.xtdb.execute :as ex]
            [manager-employment-demo :as me]
            [snow-removal-demo :as snow]))

(println "\nBuilding CSS...")
(tasks/css)

(main/start)

(def node (:biff.xtdb/node @main/system))

(println "\nSeeding the snow-removal contract into the running app...")
(snow/load! node)
(if (ex/current-state node snow/contract-id)
  (println "  visits already recorded, skipping")
  (snow/visits! node))

(println "Seeding the manager-employment contract into the running app...")
(me/load! node)
(if (ex/current-state node me/contract-id)
  (println "  already approved, skipping trigger")
  (ex/trigger! node me/contract-id
               {"$class"      "com.trustblocks.municipal.employment.execution@1.0.0.BoardApprovalRequest"
                "requestedAt" "2025-12-02T00:00:00.000Z"}
               "2025-12-02T00:00:00.000Z"))

(println "\nSeeded. Visit http://localhost:8080/contracts")
