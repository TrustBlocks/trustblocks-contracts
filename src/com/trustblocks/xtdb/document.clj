(ns com.trustblocks.xtdb.document
  "Stored documents: the files a matter's records rest on -- an engineer's
  inspection report, a contractor's pay application, signed minutes --
  kept by their content, not by a link.

  A link is not evidence: the file behind it can change or disappear. What
  makes a document evidence is its content hash, so the hash is the
  document's identity. The bytes live in a content-addressed file store
  (large binaries do not belong in XTDB rows), under
  <root>/sha256/<first two hex digits>/<hash>, written once and atomically;
  XTDB holds one record per document -- hash, size, media type -- keyed by
  the same `sha256:<hex>` string attestations already carry as their
  documentHash, so a signed credential names exactly one stored file.

  Identical bytes are one document. Who submitted a document, when, and for
  which matter are facts about a *receipt* of it (see docs/design/matters.md),
  not about the document: the same report can be received on two matters.
  Only the name a file first arrived under is kept here, as a convenience.

  Every read re-hashes what it returns. A stored file that no longer matches
  its hash -- corrupted, or altered on disk -- is refused, never served: a
  record that points at the wrong bytes is worse than one that points at
  none.

  `root` is a directory path. Keeping it an argument rather than a global
  lets tests use a temporary directory and leaves room for other backends
  (S3 and the like) behind the same three functions."
  (:require [clojure.java.io :as io]
            [xtdb.api :as xt])
  (:import [java.io File InputStream]
           [java.nio.file FileAlreadyExistsException Files Path StandardCopyOption]
           [java.nio.file.attribute FileAttribute]
           [java.security DigestInputStream MessageDigest]))

(def document-table :document)

(def default-root
  "Beside the XTDB storage, and like it never committed (/storage/ is in
  .gitignore)."
  "storage/documents")

(defn- hex [^bytes bs]
  (apply str (map #(format "%02x" (bit-and % 0xff)) bs)))

(defn- sha256-hex
  "The SHA-256 of everything `in` yields, as lower-case hex."
  [^InputStream in]
  (let [md  (MessageDigest/getInstance "SHA-256")
        buf (byte-array 65536)]
    (with-open [din (DigestInputStream. in md)]
      (while (pos? (.read din buf))))
    (hex (.digest md))))

(defn document-id
  "The id a document's hash gives it: \"sha256:<hex>\"."
  [hex-digest]
  (str "sha256:" hex-digest))

(defn- blob-file ^File [root hex-digest]
  (io/file root "sha256" (subs hex-digest 0 2) hex-digest))

(defn- hex-of [id]
  (or (second (re-matches #"sha256:([0-9a-f]{64})" (str id)))
      (throw (ex-info "Not a document id" {:trustblocks/error :invalid-document-id :id id}))))

(defn- open-source
  "An input stream over `source`: a File, a java.nio Path, a byte array, or
  an InputStream. Not a String -- clojure.java.io would read one as a URL
  and fetch it, and a stored document must come from bytes in hand."
  ^InputStream [source]
  (cond
    (bytes? source)                (io/input-stream source)
    (instance? InputStream source) source
    (instance? File source)        (io/input-stream source)
    (instance? Path source)        (io/input-stream (.toFile ^Path source))
    :else (throw (ex-info "A document must come from a file, path, byte array or stream"
                          {:trustblocks/error :invalid-document-source :type (type source)}))))

(defn store!
  "Store `source` -- a file, path, byte array or input stream -- and record
  it. Returns the document record. Storing bytes already on file changes
  nothing and returns the existing record.

  The bytes are written to a temporary file, hashed from it, then moved
  into place atomically, so a crash never leaves a partial file at a hash's
  path."
  [node root source & {:keys [filename media-type]}]
  (let [tmp-dir (doto (io/file root "tmp") (.mkdirs))
        tmp     (.toFile (Files/createTempFile (.toPath tmp-dir) "upload" ".part"
                                               (make-array FileAttribute 0)))]
    (try
      (with-open [in (open-source source)]
        (io/copy in tmp))
      (let [digest   (with-open [in (io/input-stream tmp)] (sha256-hex in))
            id       (document-id digest)
            target   (blob-file root digest)
            existing (first (xt/q node (list 'from document-table [{:xt/id id} (symbol "*")])))]
        (when-not (.exists target)
          (io/make-parents target)
          ;; Another store of the same bytes may win the race to this path;
          ;; its file is byte-for-byte this one, so losing is fine.
          (try
            (Files/move (.toPath tmp) (.toPath target)
                        (into-array [StandardCopyOption/ATOMIC_MOVE]))
            (catch FileAlreadyExistsException _ nil)))
        (or existing
            (let [record (cond-> {:xt/id               id
                                  :document/sha256     digest
                                  :document/size       (.length target)}
                           filename   (assoc :document/filename filename)
                           media-type (assoc :document/media-type media-type))]
              (xt/execute-tx node [[:put-docs document-table record]])
              record)))
      (finally
        (.delete tmp)))))

(defn get-document
  "The record for document `id`, or nil."
  [node id]
  (first (xt/q node (list 'from document-table [{:xt/id id} (symbol "*")]))))

(defn content
  "Document `id`'s bytes, after checking they still hash to `id`.

  Refuses -- :trustblocks/error :document-missing or :document-corrupt --
  rather than return bytes that are not the document the id names."
  ^bytes [root id]
  (let [digest (hex-of id)
        f      (blob-file root digest)]
    (when-not (.exists f)
      (throw (ex-info "Stored document not found" {:trustblocks/error :document-missing :id id})))
    (let [bs (Files/readAllBytes (.toPath f))]
      (when-not (= digest (sha256-hex (io/input-stream bs)))
        (throw (ex-info "Stored document does not match its hash"
                        {:trustblocks/error :document-corrupt :id id})))
      bs)))
