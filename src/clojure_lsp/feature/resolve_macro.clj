(ns clojure-lsp.feature.resolve-macro
  (:require
   [borkdude.rewrite-edn :as r]
   [clojure-lsp.db :as db]
   [clojure-lsp.feature.file-management :as f.file-management]
   [clojure-lsp.parser :as parser]
   [clojure-lsp.queries :as q]
   [clojure-lsp.refactor.edit :as edit]
   [clojure-lsp.shared :as shared]
   [clojure.java.io :as io]
   [rewrite-clj.zip :as z]
   [taoensso.timbre :as log]
   [clojure-lsp.producer :as producer]))

(defn ^:private find-function-name-position [uri row col]
  (some-> (get-in @db/db [:documents uri :text])
          (parser/loc-at-pos row col)
          edit/find-function-usage-name-loc
          z/node
          meta))

(defn find-full-macro-symbol-to-resolve [uri row col]
  (when-let [{macro-name-row :row macro-name-col :col} (find-function-name-position uri row col)]
    (let [filename (shared/uri->filename uri)
          analysis (:analysis @db/db)
          element (q/find-element-under-cursor analysis filename macro-name-row macro-name-col)
          definition (q/find-definition analysis element)]
      (when (:macro definition)
        (symbol (-> definition :ns name) (-> definition :name name))))))

(defn ^:private update-macro-resolve-for-config
  [resolved-full-symbol full-symbol config-loc]
  (-> config-loc
      z/node
      (r/assoc-in [:lint-as full-symbol] resolved-full-symbol)
      (str "\n")))

(defn ^:private resolve-macro-as [uri row col resolved-full-symbol-str kondo-config-path]
  (when-let [full-symbol (find-full-macro-symbol-to-resolve uri row col)]
    (let [kondo-config-file (io/file kondo-config-path)]
      (if (.exists ^java.io.File kondo-config-file)
        (->> (z/of-file kondo-config-file)
             (update-macro-resolve-for-config (symbol resolved-full-symbol-str) full-symbol))
        (->> (z/of-string "{}")
             (update-macro-resolve-for-config (symbol resolved-full-symbol-str) full-symbol))))))

(defn resolve-macro-as!
  [uri row col resolved-full-symbol-str kondo-config-path]
  (if-let [new-kondo-config (resolve-macro-as uri row col resolved-full-symbol-str kondo-config-path)]
    (let [document (get-in @db/db [:documents uri])]
      (io/make-parents kondo-config-path)
      (spit kondo-config-path new-kondo-config)
      (f.file-management/analyze-changes {:uri uri
                                          :version (:v document)
                                          :text (:text document)})
      (log/info (format "Resolving macro as %s. Saving setting into %s" resolved-full-symbol-str kondo-config-path)))
    (do
      (log/error (format "Could not resolve macro '%s' for path '%s'" resolved-full-symbol-str kondo-config-path))
      (producer/window-show-message (format "No macro '%s' was found to be resolved." resolved-full-symbol-str) :error))))
