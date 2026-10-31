(ns hyperphor.tunamelt.store
  "Storage backend protocol. Keys are {:realm :type :id} maps.")

(defprotocol Store
  (put* [store k obj opts]
    "Upsert obj at key k. opts: {:mode :mutable|:append-only}. Returns the entry.")
  (put-many* [store entries]
    "Put a seq of [k obj opts] in one transaction.")
  (get* [store k]
    "Return {:key :obj :meta} for k, or nil.")
  (find* [store type {:keys [realm since until attrs limit]}]
    "Return seq of {:key :obj :meta} of type, newest first.")
  (delete* [store k]
    "Delete k and its history. Returns true if something was deleted.")
  (history* [store k]
    "Return revisions of k, newest first, as {:key :obj :meta}."))
