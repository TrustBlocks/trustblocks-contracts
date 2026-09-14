(ns com.trustblocks.routes
  (:require [com.biffweb.ring :refer [defpath]]))

(defpath home "/")
(defpath contracts "/contracts")
(defpath contract "/contracts/:id")
(defpath contract-model "/contracts/:id/model")
(defpath contract-model-concerto "/contracts/:id/model/concerto")
(defpath contract-model-contract "/contracts/:id/model/contract")
