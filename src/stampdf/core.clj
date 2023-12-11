(ns stampdf.core
  (:require [clojure.java.io :refer [file]]
            [clojure.math :refer [PI]]
            [clojure.string :refer [last-index-of]])
  (:import [org.apache.pdfbox.io RandomAccessReadBufferedFile]
           [org.apache.pdfbox Loader]
           [org.apache.pdfbox.rendering PDFRenderer]
           [org.apache.pdfbox.rendering ImageType]
           [org.apache.pdfbox.pdmodel.font PDType1Font]
           [org.apache.pdfbox.pdmodel.font Standard14Fonts$FontName]
           [org.apache.pdfbox.pdmodel PDPageContentStream]
           [org.apache.pdfbox.pdmodel PDPageContentStream$AppendMode]
           [org.apache.pdfbox.util Matrix]
           [java.awt Rectangle]
           [javax.imageio ImageIO]
           [java.io File]))

(def version "0.9.0")

(def courier (PDType1Font. (Standard14Fonts$FontName/COURIER_BOLD)))
(def stamp-padding-factor 0.3)
(def a4-font-size 6)
(def baseline-correction-factor 0.20)
(def edge-margin-points 36)

(defn scale-factor [page-width]
  (/ page-width 595.276))

(defn line-width [page-width]
  (/ (scale-factor page-width) 2))

(defn render-dpi [page-width]
  (/ 72 (scale-factor page-width)))

(defn font-size [page-width]
  (Math/round (* a4-font-size (scale-factor page-width))))

(defn edge-margin [page-width]
  (* edge-margin-points (scale-factor page-width)))

(defn text-dimensions [font-size text]
  [(* font-size (/ (.getStringWidth courier text) 1000))
   (* font-size (/ (.getHeight (.getBoundingBox courier)) 1000))])

(defn padding [text-height]
  (* text-height stamp-padding-factor))

(defn textbox-dimensions [text-dimensions]
  (let [[tw th] text-dimensions
        paddings (* 2 (padding th))]
    [(+ tw paddings) (+ th paddings)]))

(defn variance [image-data]
  (let [intensities (for [x image-data] (mod x 256))
        average (double (/ (reduce + intensities) (count intensities)))]
    (/ (reduce + (for [intensity intensities]
                   (* (- intensity average) (- intensity average))))
       (count intensities))))

(defn candidate-positions [page-width page-height box-width box-height]
  (let [margin (edge-margin page-width)]
    [;; top edge, left
     [:regular margin (- page-height box-height margin)]
     ;; top edge, middle
     [:regular (/ (- page-width box-width) 2) (- page-height box-height margin)]
     ;; top edge, right
     [:regular (- page-width box-width margin)  (- page-height box-height margin)]
     ;; bottom edge, left
     [:regular margin margin]
     ;; bottom edge, middle
     [:regular (/ (- page-width box-width) 2) margin]
     ;; bottom edge, right
     [:regular (- page-width box-width margin) margin]
     ;; left edge, top
     [:ccw margin (- page-height box-width margin)]
     ;; left edge, middle
     [:ccw margin (/ (- page-height box-width) 2)]
     ;; left edge, bottom
     [:ccw margin margin]
     ;; right edge, top
     [:cw (- page-width box-height margin) (- page-height box-width margin)]
     ;; right edge, middle
     [:cw (- page-width box-height margin) (/ (- page-height box-width) 2)]
     ;; right edge, bottom
     [:cw (- page-width box-height margin) margin]]))

(defn render-stamp [pdf page x y text orientation]
  (let [cs (PDPageContentStream. pdf page PDPageContentStream$AppendMode/APPEND true true)
        page-width (.getWidth (.getMediaBox page)) 
        [_ th :as text-dims] (text-dimensions (font-size page-width) text)
        [bw bh] (textbox-dimensions text-dims)
        pad (padding th)
        baseline-correction (* th baseline-correction-factor)]
    (if (= orientation :regular)
      (.addRect cs x y bw bh)
      (.addRect cs x y bh bw))
    (.setNonStrokingColor cs 1.)
    (.setStrokingColor cs 0.)
    (.setLineWidth cs (line-width page-width))
    (.fillAndStroke cs)
    (.setFont cs courier (font-size page-width))
    (.beginText cs)
    (case orientation
      :regular (.newLineAtOffset cs (+ x pad) (+ y pad baseline-correction))
      :ccw (do
             (.setTextMatrix cs (Matrix/getRotateInstance (/ PI 2) x y))
             (.newLineAtOffset cs pad (- (+ baseline-correction pad) bh)))
      :cw (do
            (.setTextMatrix cs (Matrix/getRotateInstance (- (/ PI 2)) x y))
            (.newLineAtOffset cs (- pad bw) (+ baseline-correction pad))))
    (.setNonStrokingColor cs 0.)
    (.showText cs text)
    (.endText cs)
    (.close cs)))

(defn intensities [rendered [x y w h]]
  (.getData (.getDataBuffer (.getData rendered (Rectangle. x y w h)))))

(defn translate [page-width render-height [orientation x y] w h]
  (let [->img #(/ % (scale-factor page-width))]
    (if (= orientation :regular)
      [(->img x) (- render-height (->img (+ y h))) (->img w) (->img h)]
      [(->img x) (- render-height (->img (+ y w))) (->img h) (->img w)])))

(defn best-position [renderer [page-width page-height] [box-width box-height] page-num]
  (let [rendered (.renderImageWithDPI renderer page-num (render-dpi page-width) ImageType/GRAY)
        render-height (.getHeight rendered)]
    (->> (for [position (candidate-positions page-width page-height box-width box-height)]
           {:position position
            :variance (->> (translate page-width render-height position box-width box-height)
                           (intensities rendered)
                           variance)})
         (sort-by :variance)
         first
         :position)))

(defn output-file [input-file {:keys [output in-place]}]
  (cond
    output output
    in-place input-file
    :else (if-let [index (last-index-of input-file \.)]
            (str (subs input-file 0 index) ".stamped" (subs input-file index))
            (str input-file "-stamped"))))

(defn process-pdf [input-file text output options]
  (let [pdf (Loader/loadPDF (RandomAccessReadBufferedFile. input-file))
        renderer (PDFRenderer. pdf)
        temp-file (File/createTempFile "stampdf-" ".pdf")]
    (doseq [page-num (range (.getNumberOfPages pdf))]
      (let [page (.getPage pdf page-num)
            page-width (.getWidth (.getMediaBox page))
            page-height (.getHeight (.getMediaBox page))
            page-size [page-width page-height]
            [box-width box-height :as box-size] (textbox-dimensions (text-dimensions (font-size page-width) text))
            [orientation x y] (best-position renderer page-size box-size page-num)]
        (render-stamp pdf page x y text orientation)))
    (.save pdf temp-file)
    (.renameTo temp-file (File. output))
    [true output]))

(defn run-internal [input-file text options]
  (let [output (output-file input-file options)]
    (if (and (.exists (File. output))
             (not (:overwrite options))
             (not (:in-place options)))
      [false "refusing to overwrite existing file, see --help to force it"]
      (process-pdf input-file text output options))))

(defn run [input-file text options]
  (try
    (run-internal input-file text options)
    (catch Exception e
      [false (str e)])))
