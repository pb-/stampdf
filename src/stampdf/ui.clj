(ns stampdf.ui
  (:require [clojure.string :as string]
            [clojure.tools.cli :refer [parse-opts]]
            [stampdf.core :refer [run version]])
  (:gen-class))

(def cli-options
  [["-i" "--in-place" "overwrite the input file, use with care"]
   ["-o" "--output FILENAME" "filename of the stamped output file"]
   ["-w" "--overwrite" "overwrite output file if it exists"]
   ["-h" "--help"]])

(defn error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (string/join \newline errors)))

(defn usage [options-summary]
  (->> [(str "stampdf " version " -- stamp a short text string in the most suitable location onto every page of a PDF")
        ""
        "usage: stampdf [OPTION]... INPUT-FILE TEXT"
        ""
        "options:"
        options-summary
        ""
        "please file bugs at https://github.com/pb-/stampdf"]
       (string/join \newline)))

(defn validate-args [args]
  (let [{:keys [options arguments errors summary] :as parsed} (parse-opts args cli-options)]
    (cond
      (:help options) {:exit-message (usage summary) :ok? true}
      errors {:exit-message (error-msg errors)}
      (and (:in-place options) (:output options)) {:exit-message "error: --in-pace and --output are mutually exclusive"}
      (= (count arguments) 2) parsed
      :else {:exit-message (usage summary)})))

(defn exit [status msg]
  (println msg)
  (System/exit status))

(defn -main [& args]
  (let [{:keys [arguments options exit-message ok?]} (validate-args args)]
    (if exit-message
      (exit (if ok? 0 1) exit-message)
      (let [[input-file text] arguments
            [ok? message] (run input-file text options)]
        (exit (if ok? 0 1) message)))))
