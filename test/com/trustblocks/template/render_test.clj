(ns com.trustblocks.template.render-test
  "Contract View's renderer: TemplateMark's template layer here, CommonMark
  plus tables for the markdown -- and values that can never become markup."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [com.trustblocks.template.library :as library]
            [com.trustblocks.template.render :as render]))

(defn- html [grammar data] (render/render-html grammar data))

(defn- value [s] (str "<span class=\"text-blue-700 font-medium\">" s "</span>"))

(deftest variables-and-formats
  (testing "a plain value, highlighted"
    (is (= (str "<p>Hello " (value "Ada") "</p>\n") (html "Hello {{name}}" {"name" "Ada"}))))
  (testing "amount patterns"
    (is (re-find #"12,000\.00" (html "{{q as \"0,0.00\"}}" {"q" 12000.0})))
    (is (re-find #"1,250,401\b" (html "{{q as \"0,0\"}}" {"q" 1250400.99})))
    (is (re-find #"1 250 400,99" (html "{{q as \"0 0,00\"}}" {"q" 1250400.99}))))
  (testing "DateTime tokens, at the value's own offset"
    (is (re-find #"July 14, 2026" (html "{{d as \"MMMM D, YYYY\"}}" {"d" "2026-07-14T23:00:00.000-04:00"})))
    (is (re-find #"14/07/2026" (html "{{d as \"DD/MM/YYYY\"}}" {"d" "2026-07-14T23:00:00.000-04:00"}))))
  (testing "a format that does not apply falls back to the plain value"
    (is (re-find #"dddd-ish" (html "{{d as \"dddd\"}}" {"d" "dddd-ish"}))))
  (testing "a name the data lacks is shown dimmed, not dropped"
    (is (re-find #"italic text-gray-400\">\{\{nope\}\}" (html "{{nope}}" {})))))

(deftest blocks-rebind-scope
  (testing "#optional renders its body with this, or its else branch"
    (is (re-find #"present: .*Acme" (html "present: {{#optional c}}{{this}}{{else}}blank{{/optional}}" {"c" "Acme"})))
    (is (re-find #"present: blank" (html "present: {{#optional c}}{{this}}{{else}}blank{{/optional}}" {}))))
  (testing "inside a block a sibling property is out of scope, as in Accord's engine"
    (is (re-find #"\{\{other\}\}" (html "{{#optional c}}{{other}}{{/optional}}" {"c" "x" "other" "y"}))))
  (testing "#if on a boolean"
    (is (re-find #">yes<|yes" (html "{{#if f}}yes{{else}}no{{/if}}" {"f" true})))
    (is (re-find #"no" (html "{{#if f}}yes{{else}}no{{/if}}" {"f" false})))))

(deftest lists
  (let [items {"items" [{"p" "1.1" "q" 2.5} {"p" "1.2" "q" 1000.0}]}]
    (testing "#ulist: one item per element, scope bound to the element"
      (let [out (html "Items:\n\n{{#ulist items}}\n{{p}} at {{q as \"0,0.00\"}}\n{{/ulist}}\n\nAfter." items)]
        (is (re-find #"<ul>\n<li>.*1\.1.* at .*2\.50.*</li>\n<li>.*1\.2.* at .*1,000\.00.*</li>\n</ul>" out))
        (is (re-find #"<p>After\.</p>" out))))
    (testing "#olist numbers them"
      (is (re-find #"<ol>\n<li>" (html "{{#olist items}}\n{{p}}\n{{/olist}}" items))))
    (testing "an empty array renders nothing, and the text around it survives"
      (is (= "<p>Before.</p>\n<p>After.</p>\n"
             (html "Before.\n\n{{#ulist items}}\n{{p}}\n{{/ulist}}\n\nAfter." {"items" []}))))))

(deftest commonmark-and-tables
  (is (re-find #"<table>[\s\S]*<td>.*Acme.*</td>" (html "| A | B |\n| --- | --- |\n| x | {{c}} |" {"c" "Acme"})))
  (is (re-find #"<strong>Bold</strong> and <em>em</em>" (html "**Bold** and *em*" {})))
  (is (re-find #"<p>___ \$5</p>" (html "\\_\\_\\_ \\$5" {})) "escapes resolve to the characters"))

(deftest nothing-becomes-markup
  (testing "a value's markdown and HTML characters stay characters"
    (let [out (html "{{c}}" {"c" "*Acme* <b>LLC</b> & \"co\""})]
      (is (re-find #"\*Acme\* &lt;b&gt;LLC&lt;/b&gt; &amp; &quot;co&quot;" out))
      (is (not (re-find #"<em>|<b>" out)))))
  (testing "raw HTML in a grammar is escaped, not rendered"
    (is (not (re-find #"<script>" (html "<script>alert(1)</script>" {}))))))

(deftest review-findings-on-the-renderer
  ;; Each found by code review of the PR that introduced this renderer.
  (testing "a value inside a link or image stays inside its attribute"
    (let [out (html "[site]({{u}}) ![{{alt}}](https://x.test/i.png)"
                    {"u" "https://example.gov/a?b=1&c=2" "alt" "Town \"seal\""})]
      (is (re-find #"href=\"https://example.gov/a\?b=1&amp;c=2\"" out))
      (is (re-find #"alt=\"Town &quot;seal&quot;\"" out))
      (is (not (re-find #"=\"<span" out)))))
  (testing "javascript: links do not survive"
    (is (not (re-find #"javascript:" (html "[bad](javascript:alert(1))" {})))))
  (testing "a stray or mismatched closer is literal text, not the end of the document"
    (is (re-find #"A \{\{/if\}\} B .*Ada" (html "A {{/if}} B {{x}}" {"x" "Ada"})))
    (is (re-find #"\{\{else\}\}" (html "top {{else}} level" {})))
    (is (re-find #"(?s)\{\{/optional\}\}.*<p>after</p>" (html "{{#ulist xs}}\n{{this}} {{/optional}}\n{{/ulist}}\n\nafter" {"xs" ["a"]}))))
  (testing "a DateTime with no offset is read as UTC and still formatted"
    (is (re-find #"July 14, 2026" (html "{{d as \"MMMM D, YYYY\"}}" {"d" "2026-07-14T10:00:00"}))))
  (testing "a MonetaryAmount formats with its currency where the pattern asks"
    (let [m {"$class" "org.accordproject.money@0.3.0.MonetaryAmount" "doubleValue" 2000500.0 "currencyCode" "USD"}]
      (is (re-find #">2,000,500.00 USD<" (html "{{m as \"0,0.00 CCC\"}}" {"m" m})))
      (is (re-find #">\$2,000,500.00<" (html "{{m as \"K0,0.00\"}}" {"m" m}))))))

(deftest our-templates-render-completely
  ;; Every variable in both real templates resolves against its own sample:
  ;; no dimmed {{token}} anywhere.
  (doseq [t ["manager-employment-contract" "street-resurfacing-contract"]]
    (let [out (html (slurp (io/file (library/dir t) "text" "grammar.tem.md"))
                    (json/parse-string (library/sample t)))]
      (is (not (re-find #"\{\{" out)) t)))
  (testing "STR-27's 37 pay items, four bullet lists, and two tables"
    (let [out (html (slurp (io/file (library/dir "street-resurfacing-contract") "text" "grammar.tem.md"))
                    (json/parse-string (library/sample "street-resurfacing-contract")))]
      (is (= 50 (count (re-seq #"<li>" out))))
      (is (= 2 (count (re-seq #"<table>" out)))))))
