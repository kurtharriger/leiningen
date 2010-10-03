(ns leiningen.util.maven
  (:use [leiningen.core :only [repositories-for]]
        [clojure.java.io :only [file reader]])
  (:import [org.apache.maven.model Build Model Parent Dependency
            Exclusion Repository Scm License MailingList Resource]
           [org.apache.maven.project.artifact ProjectArtifactMetadata]
           [org.apache.maven.settings MavenSettingsBuilder]
           [org.apache.maven.artifact.repository ArtifactRepositoryFactory
            DefaultArtifactRepository]
           [org.apache.maven.artifact.factory ArtifactFactory]
           [org.apache.maven.artifact.repository ArtifactRepositoryPolicy]
           [org.apache.maven.artifact.repository.layout
            ArtifactRepositoryLayout]
           [org.codehaus.plexus.embed Embedder]))

;; Welcome to the absurdist self-parodying world of Dependency Injection
(def container (.getContainer (doto (Embedder.) (.start))))

(def layout
  (.lookup container ArtifactRepositoryLayout/ROLE "default"))

(def policy
     (ArtifactRepositoryPolicy. true
                                ArtifactRepositoryPolicy/UPDATE_POLICY_DAILY
                                ArtifactRepositoryPolicy/CHECKSUM_POLICY_FAIL))

(defn make-settings []
  (.buildSettings (.lookup container MavenSettingsBuilder/ROLE)))

;; repositories

(defn make-local-repo []
  (let [path (.getLocalRepository (make-settings))
        url (if (.startsWith path "file:") path (str "file://" path))]
    (-> (.lookup container ArtifactRepositoryFactory/ROLE)
        (.createDeploymentArtifactRepository
         "local" url layout true))))

(defn make-remote-repo [[name url]]
  (-> (.lookup container ArtifactRepositoryFactory/ROLE)
      (.createArtifactRepository
       name url layout policy policy)))

(defn add-metadata [artifact pomfile]
  (.addMetadata artifact (ProjectArtifactMetadata. artifact pomfile)))

(defn make-artifact [model]
  (.createArtifactWithClassifier
   (.lookup container ArtifactFactory/ROLE)
   (.getGroupId model)
   (.getArtifactId model)
   (.getVersion model)
   (.getPackaging model)
   nil))

(defn make-remote-artifact [name group version]
  (.createArtifact
   (.lookup container ArtifactFactory/ROLE)
   (or group name) name
   version "compile" "jar"))

;; git

(defn- read-git-ref
  "Reads the commit SHA1 for a git ref path."
  [git-dir ref-path]
  (.trim (slurp (str (file git-dir ref-path)))))

(defn- read-git-head
  "Reads the value of HEAD and returns a commit SHA1."
  [git-dir]
  (let [head (.trim (slurp (str (file git-dir "HEAD"))))]
    (if-let [ref-path (second (re-find #"ref: (\S+)" head))]
      (read-git-ref git-dir ref-path)
      head)))

(defn- read-git-origin
  "Reads the URL for the remote origin repository."
  [git-dir]
  (with-open [rdr (reader (file git-dir "config"))]
    (->> (map #(.trim %) (line-seq rdr))
         (drop-while #(not= "[remote \"origin\"]" %))
         (next)
         (take-while #(not (.startsWith % "[")))
         (map #(re-matches #"url\s*=\s*(\S*)\s*" %))
         (filter identity)
         (first)
         (second))))

(defn- parse-github-url
  "Parses a GitHub URL returning a [username repo] pair."
  [url]
  (when url
    (next
     (or
      (re-matches #"(?:git@)?github.com:([^/]+)/([^/]+).git" url)
      (re-matches #"[^:]+://(?:git@)?github.com/([^/]+)/([^/]+).git" url)))))

(defn- github-urls [url]
  (when-let [[user repo] (parse-github-url url)]
    {:public-clone (str "git://github.com/" user "/" repo ".git")
     :dev-clone (str "ssh://git@github.com/" user "/" repo ".git")
     :browse (str "http://github.com/" user "/" repo)}))

(defn- make-git-scm [git-dir]
  (try
    (let [origin (read-git-origin git-dir)
          head (read-git-head git-dir)
          urls (github-urls origin)
          scm (Scm.)]
      (.setUrl scm (:browse urls))
      (.setTag scm head)
      (when (:public-clone urls)
        (.setConnection scm (str "scm:git:" (:public-clone urls))))
      (when (:dev-clone urls)
        (.setDeveloperConnection scm (str "scm:git:" (:dev-clone urls))))
      scm)
    (catch java.io.FileNotFoundException e
      nil)))

;; model

(defn make-parent [parent version & {:as opts}]
  (doto (Parent.)
    (.setArtifactId (name parent))
    (.setGroupId (or (namespace parent) (name parent)))
    (.setVersion version)
    (.setRelativePath (:relative-path opts))
    (.setModelEncoding (:model-encoding opts))))

(defn make-exclusion [excl]
  (doto (Exclusion.)
    (.setGroupId (or (namespace excl) (name excl)))
    (.setArtifactId (name excl))))

(defn make-dependency
  "Makes a dependency from a seq. The seq (usually a vector) should
contain a symbol to define the group and artifact id, then a version
string. The remaining arguments are combined into a map. The value for
the :classifier key (if present) is the classifier on the
dependency (as a string). The value for the :exclusions key, if
present, is a seq of symbols, identifying group ids and artifact ids
to exclude from transitive dependencies."
  [[dep version & extras]]
  (let [extras-map (apply hash-map extras)
        exclusions (:exclusions extras-map)
        classifier (:classifier extras-map)
        type (:type extras-map)
        es (map make-exclusion exclusions)]
    (doto (Dependency.)
      ;; Allow org.clojure group to be omitted from clojure/contrib deps.
      (.setGroupId (if (and (nil? (namespace dep))
                            (re-find #"^clojure(-contrib)?$" (name dep)))
                     "org.clojure"
                     (or (namespace dep) (name dep))))
      (.setArtifactId (name dep))
      (.setVersion version)
      (.setClassifier classifier)
      (.setType (or type "jar"))
      (.setExclusions es))))

(defn make-repository [[id settings]]
  (let [repo (Repository.)]
    (.setId repo id)
    (if (string? settings)
      (.setUrl repo settings)
      (.setUrl repo (:url settings)))
    repo))

(defn make-license [{:keys [name url distribution comments]}]
  (doto (License.)
    (.setName name)
    (.setUrl url)
    (.setDistribution (and distribution (clojure.core/name distribution)))
    (.setComments comments)))

(defn make-mailing-list [{:keys [name archive other-archives
                                 post subscribe unsubscribe]}]
  (let [mailing-list (MailingList.)]
    (doto mailing-list
      (.setName name)
      (.setArchive archive)
      (.setPost post)
      (.setSubscribe subscribe)
      (.setUnsubscribe unsubscribe))
    (doseq [other-archive other-archives]
      (.addOtherArchive mailing-list other-archive))
    mailing-list))

(defn- relative-path
  [project path-key]
  (.replace (path-key project) (str (:root project) "/") ""))

(defmacro ^:private add-a-resource [build method resource-path]
  `(let [resource# (Resource.)]
     (.setDirectory resource# ~resource-path)
     (~(symbol (name method)) ~build [resource#])))

(defn make-model [project]
  (let [model (doto (Model.)
                (.setModelVersion "4.0.0")
                (.setArtifactId (:name project))
                (.setName (:name project))
                (.setVersion (:version project))
                (.setGroupId (:group project))
                (.setDescription (:description project))
                (.setUrl (:url project)))
        build (doto (Build.)
                (add-a-resource :.setResources
                                (relative-path project :resources-path))
                (add-a-resource :.setTestResources
                                (relative-path project :test-resources-path))
                (.setSourceDirectory (relative-path project :source-path))
                (.setTestSourceDirectory (relative-path project :test-path)))]
    (.setBuild model build)
    (doseq [dep (:dependencies project)]
      (.addDependency model (make-dependency dep)))
    (doseq [repo (repositories-for project)]
      (.addRepository model (make-repository repo)))
    (when-let [scm (make-git-scm (file (:root project) ".git"))]
      (.setScm model scm))
    (when-let [parent (:parent project)]
      (.setParent model (apply make-parent parent)))
    (doseq [license (concat (keep #(% project)
                                  [:licence :license])
                            (:licences project)
                            (:licenses project))]
      (.addLicense model (make-license license)))
    (doseq [mailing-list (concat (if-let [ml (:mailing-list project)] [ml] [])
                                 (:mailing-lists project))]
      (.addMailingList model (make-mailing-list mailing-list)))
    model))
