(ns hyperphor.tunamelt.store
  "Storage backend protocol. Keys are {:realm :type :id} maps.
  In cljs every method returns a Promise of the documented value.")

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
    "Return revisions of k, newest first, as {:key :obj :meta}.")
  (types* [store realm]
    "Return {type count} for all stored types, optionally limited to realm.")
  (referrers* [store uri opts]
    "Return entries (plus :attr) whose attributes hold uri. opts: :attr, :type.")
  (search* [store query opts]
    "Return entries (plus :rank, :snippet) whose text matches query, best first.
    opts: :type, :realm, :limit, :raw?."))
