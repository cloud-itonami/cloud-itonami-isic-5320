(ns courierops.facts
  "Per-jurisdiction courier-operation AND parcel-liability-disclosure
  regulatory catalog -- the spec-basis table the Courier Governor checks
  every `:jurisdiction/assess` proposal against ('did the advisor cite
  an OFFICIAL public source for this jurisdiction's requirements, or did
  it invent one?').

  Last-mile courier carriage has its own regulatory shape, distinct from
  the community-freight vertical (`cloud-itonami-isic-4920`): the
  operator is typically a light-goods-vehicle courier (in Japan a 貨物軽
  自動車運送事業 notification, not a full freight-carrier license), and
  the liability regime that matters at settlement is the PARCEL
  liability-limit disclosure (in Japan the 標準宅配便運送約款's
  責任限度額), not a general freight consignment's. Each jurisdiction
  entry below therefore cites BOTH the courier-operation law AND a
  SEPARATE parcel-liability-disclosure basis.

  Coverage is reported HONESTLY (see `coverage`), the same discipline
  every sibling actor's `facts` namespace uses: a jurisdiction not in
  this table has NO spec-basis, full stop -- the advisor must not
  fabricate one, and the governor holds if it tries. Where a seeded
  jurisdiction's courier regime has a real structural quirk (GBR/DEU
  light-vehicle licensing exemptions), the entry says so instead of
  pretending a uniform license exists."
  )

(def catalog
  "iso3 -> requirement map. `:required-evidence` mirrors the courier
  delivery-registration/operator-notification/tracking evidence set
  (PLUS a parcel-liability-disclosure record for every seeded
  jurisdiction); `:legal-basis` / `:owner-authority` / `:provenance`
  are the citation the governor requires before any
  `:jurisdiction/assess` proposal can commit.
  `:liability-owner-authority` / `:liability-legal-basis` /
  `:liability-provenance` are the SEPARATE parcel-liability-disclosure
  citation the governor's `pod`/settlement checks are grounded in."
  {"JPN" {:name "Japan"
          :owner-authority "国土交通省 (Ministry of Land, Infrastructure, Transport and Tourism, MLIT)"
          :legal-basis "貨物自動車運送事業法 第36条 (貨物軽自動車運送事業の届出)"
          :national-spec "標準貨物軽自動車運送約款 (国土交通省告示)"
          :provenance "https://www.mlit.go.jp/jidosha/jidosha_tk4_000004.html"
          :required-evidence ["配送登録記録 (delivery-registration record)"
                              "軽貨物運送事業届出記録 (courier-operator-notification record)"
                              "追跡記録 (tracking record)"
                              "宅配便責任限度額開示記録 (parcel-liability-disclosure record)"]
          :liability-owner-authority "国土交通省 (MLIT)"
          :liability-legal-basis "標準宅配便運送約款 第25条 責任限度額 / 商法 運送営業規定"
          :liability-provenance "https://www.mlit.go.jp/jidosha/jidosha_tk4_000004.html"}
   "USA" {:name "United States"
          :owner-authority "Federal Motor Carrier Safety Administration (FMCSA)"
          :legal-basis "49 C.F.R. Parts 360-399 (motor-carrier operating authority and safety)"
          :national-spec "FMCSA operating-authority registration for for-hire carriers of property"
          :provenance "https://www.fmcsa.dot.gov/regulations"
          :required-evidence ["Delivery-registration record"
                              "Courier-operator-authorization record"
                              "Tracking record"
                              "Parcel-liability-disclosure record"]
          :liability-owner-authority "Surface Transportation Board (STB) / FMCSA"
          :liability-legal-basis "Carmack Amendment (49 U.S.C. §14706, motor-carrier cargo liability)"
          :liability-provenance "https://www.fmcsa.dot.gov/regulations/title49/section/14706"}
   "GBR" {:name "United Kingdom"
          :owner-authority "Driver and Vehicle Standards Agency (DVSA)"
          :legal-basis "Road Traffic Act 1988 (light-goods-vehicle courier operations; vans ≤3.5t are exempt from GB domestic O-licensing under the Goods Vehicles (Licensing of Operators) Act 1995 -- reported honestly, not papered over)"
          :national-spec "DVSA roadworthiness/driver standards for light goods vehicles"
          :provenance "https://www.gov.uk/government/organisations/driver-and-vehicle-standards-agency"
          :required-evidence ["Delivery-registration record"
                              "Courier-operator-notification record"
                              "Tracking record"
                              "Parcel-liability-disclosure record"]
          :liability-owner-authority "Department for Business and Trade / Competition and Markets Authority"
          :liability-legal-basis "Consumer Rights Act 2015 (services: parcel-carrier liability terms must be transparent and not unfair)"
          :liability-provenance "https://www.legislation.gov.uk/ukpga/2015/15"}
   "DEU" {:name "Germany"
          :owner-authority "Bundesamt für Logistik und Mobilität (BALM)"
          :legal-basis "Güterkraftverkehrsgesetz (GüKG; KEP-Dienste mit Fahrzeugen ≤3,5t sind von der GüKG-Erlaubnispflicht ausgenommen -- reported honestly)"
          :national-spec "BALM Markt- und Sicherheitsaufsicht für Güterkraftverkehr/KEP"
          :provenance "https://www.gesetze-im-internet.de/gukg/"
          :required-evidence ["Zustellregistrierungsnachweis (delivery-registration record)"
                              "KEP-Betriebsanzeigenachweis (courier-operator-notification record)"
                              "Sendungsverfolgungsnachweis (tracking record)"
                              "Paket-Haftungsoffenlegungsnachweis (parcel-liability-disclosure record)"]
          :liability-owner-authority "Bundesamt für Logistik und Mobilität (BALM)"
          :liability-legal-basis "Handelsgesetzbuch (HGB) §407 ff. Frachtgeschäft (gilt auch für KEP-/Kurierbeförderung)"
          :liability-provenance "https://www.gesetze-im-internet.de/hgb/"}})

(defn spec-basis
  "The jurisdiction's requirement map, or nil -- nil means NO spec-basis,
  and the governor must hold any proposal that tries to dispatch or
  settle a delivery on it."
  [iso3]
  (get catalog iso3))

(defn coverage
  "Honest coverage report: how many of the requested jurisdictions actually
  have a spec-basis entry. Never report a missing jurisdiction as covered."
  ([] (coverage (keys catalog)))
  ([iso3s]
   (let [have (filter catalog iso3s)
         missing (remove catalog iso3s)]
     {:requested (count iso3s)
      :covered (count have)
      :covered-jurisdictions (vec (sort have))
      :missing-jurisdictions (vec (sort missing))
      :note (str "cloud-itonami-isic-5320 R0: " (count catalog)
                 " jurisdictions seeded with an official spec-basis. "
                 "This is a starting catalog, not a survey of all ~194 "
                 "jurisdictions -- extend `courierops.facts/catalog`, "
                 "never fabricate a jurisdiction's requirements.")})))

(defn required-evidence-satisfied?
  "Does `submitted` (a set/coll of evidence keywords or strings) satisfy
  every evidence item listed for `iso3`? Missing spec-basis -> never
  satisfied."
  [iso3 submitted]
  (when-let [{:keys [required-evidence]} (spec-basis iso3)]
    (let [need (count required-evidence)
          have (count (filter (set submitted) required-evidence))]
      (= need have))))

(defn evidence-checklist [iso3]
  (:required-evidence (spec-basis iso3) []))

(defn liability-spec-basis
  "The jurisdiction's parcel-liability-disclosure requirement map, or
  nil -- nil means this jurisdiction has NO formal parcel-liability-
  disclosure regime this catalog is aware of. In this R0 catalog all
  four seeded jurisdictions actually have one, reported honestly."
  [iso3]
  (when-let [sb (spec-basis iso3)]
    (when (:liability-owner-authority sb)
      (select-keys sb [:liability-owner-authority :liability-legal-basis :liability-provenance]))))
