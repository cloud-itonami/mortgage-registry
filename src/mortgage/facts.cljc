(ns mortgage.facts
  "Per-jurisdiction real-property-secured lending (mortgage) catalog: the
  PROCEDURE a borrower goes through, the PUBLIC SUPPORT programmes that
  jurisdiction operates, and the ORGANIZATIONS that own each of those.

  Three planes, one key. `cloud-itonami-isic-6492` (`credit.facts`) holds the
  generic consumer-credit disclosure/licensing requirements and
  `cloud-itonami-isic-6810` (`realty.facts`) holds the property-transfer/title
  requirements; neither holds the mortgage-specific middle -- how the loan is
  secured against the property, and what public support (state guarantee,
  subsidised loan, tax relief) exists for it. That middle is this catalog.

  SAME HONESTY CONTRACT as every sibling `facts` namespace: a jurisdiction not
  in `catalog` has NO spec-basis here and must be reported as uncovered, never
  silently defaulted. Seed values are drawn from each jurisdiction's official
  source and were fetched, not recalled -- `:retrieved-at` records when, and
  `:verification` records verbatim WHAT WAS NOT verified in that same session.
  This is a seeded catalog, not a from-scratch survey of all ~194
  jurisdictions. Extending coverage is a research task, not a code task --
  never invent a jurisdiction's requirements, programme figures or eligibility
  ceilings to make coverage look bigger.

  NON-ADJUDICATING, like `saisei`: `:support` entries disclose a programme's
  published eligibility conditions as SIGNALS. This catalog never concludes
  that a given person qualifies for a given programme, and never computes an
  affordability or entitlement outcome. Whoever operates a live instance
  (a licensed lender, a broker, a housing counsellor) applies the programme's
  own current rules and bears that jurisdiction's liability."
  (:require [clojure.string :as str]))

(def catalog
  "iso3 -> {:procedure, :support, :organizations, :verification}.

  `:procedure` is the securing/disclosure path specific to a loan secured on
  real property. `:support` is a vector of public support programmes, each
  with the operator that actually runs it. `:organizations` names the bodies
  referenced by the other two planes so the organization plane can be queried
  on its own (and joined to `cloud-itonami-assoc-*` by `:isic` + `:country`)."
  {"JPN"
   {:name "Japan"
    :procedure
    {:owner-authority "法務局 (Legal Affairs Bureau) — 抵当権設定登記; 金融庁 (FSA) — 貸付業規制"
     :legal-basis "不動産登記法 (Real Property Registration Act) / 民法第369条 (抵当権)"
     :provenance "https://www.moj.go.jp/MINJI/"
     :security-instrument "抵当権 (hypothec), perfected by 登記 at 法務局"
     :steps ["金融機関へ事前審査 (pre-screening) の申込"
             "正式申込・本審査 (income, collateral valuation, 団体信用生命保険加入)"
             "金銭消費貸借契約および抵当権設定契約の締結"
             "決済・実行と同日に司法書士が所有権移転登記・抵当権設定登記を申請"]
     :cross-reference {:transfer-side "cloud-itonami-isic-6810 realty.facts/catalog \"JPN\""
                       :credit-side "cloud-itonami-isic-6492 credit.facts/catalog \"JPN\""}}
    :support
    [{:id "jpn.flat35"
      :name "【フラット３５】 (Flat 35 long-term fixed-rate housing loan)"
      :kind :securitised-fixed-rate-loan
      :operator "住宅金融支援機構 (Japan Housing Finance Agency, JHF) + 民間金融機関"
      :operator-model "民間金融機関と住宅金融支援機構が提携して提供する"
      :provenance "https://www.flat35.com/loan/lineup/flat35/conditions/index.html"
      :retrieved-at "2026-08-01"
      :eligibility-signals
      ["申込時の年齢が満70歳未満の方 (親子リレー返済利用時は満70歳以上も可)"
       "日本国籍の方、永住許可を受けている方または特別永住者の方"
       "総返済負担率: 年収400万円未満は30％以下、年収400万円以上は35％以下"
       "住宅金融支援機構が定めた技術基準に適合する住宅 (適合証明が必要)"]
      :terms {:amount "100万円以上1億2,000万円以下（1万円単位）で、建設費または購入価額以内"
              :term "15年以上で、かつ、80歳から申込時年齢を引いた年数か35年のいずれか短い年数"
              :purpose "お申込ご本人またはそのご親族の方がお住まいになる新築住宅の建設・購入資金または中古住宅の購入資金"}}
     {:id "jpn.housing-loan-tax-deduction"
      :name "住宅借入金等特別控除 (Housing Loan Special Tax Deduction)"
      :kind :income-tax-relief
      :operator "国税庁 (National Tax Agency)"
      :provenance "https://www.nta.go.jp/taxes/shiraberu/taxanswer/shotoku/1213.htm"
      :retrieved-at "2026-08-01"
      :eligibility-signals
      ["住宅ローンを利用して住宅を取得し、取得後6か月以内に居住の用に供すること"
       "この特別控除を受ける年分の12月31日まで引き続き居住の用に供していること"
       "床面積が50平方メートル以上 (特例により40平方メートル以上50平方メートル未満の場合あり)"
       "合計所得金額が3,000万円以下 (特例適用時は1,000万円以下)"
       "10年以上にわたり分割して返済する方法である借入金であること"]
      :required-docs ["（特定増改築等）住宅借入金等特別控除額の計算明細書"
                      "住宅取得資金に係る借入金の年末残高等証明書"
                      "登記事項証明書"]
      :rate-note "The fetched タックスアンサー page stated the deduction rate/period for residences occupied 2021-01-01〜2021-12-31 (控除期間10年・年末残高等×1％・上限50万円). The rate and ceiling applicable to the CURRENT year were NOT verified in this session -- read the source before quoting a rate."}]
    :organizations
    [{:id "jhf" :name-en "Japan Housing Finance Agency" :name-local "独立行政法人 住宅金融支援機構"
      :role :programme-operator :url "https://www.jhf.go.jp/" :isic "6492" :country "JPN"}
     {:id "fsa-jp" :name-en "Financial Services Agency" :name-local "金融庁"
      :role :prudential-regulator :url "https://www.fsa.go.jp/" :isic "6419" :country "JPN"}
     {:id "nta-jp" :name-en "National Tax Agency" :name-local "国税庁"
      :role :tax-authority :url "https://www.nta.go.jp/" :isic "8411" :country "JPN"}
     {:id "moj-legal-affairs-bureau" :name-en "Legal Affairs Bureau" :name-local "法務局"
      :role :land-registry :url "https://www.moj.go.jp/MINJI/" :isic "6810" :country "JPN"}]
    :verification
    {:fetched-this-session ["flat35.com conditions page (verbatim)"
                            "nta.go.jp タックスアンサー No.1213 (verbatim)"]
     :not-verified ["current-year 住宅ローン控除 rate/ceiling"
                    "子育てエコホーム等の年度限定補助事業 (not seeded at all)"]}}

   "USA"
   {:name "United States"
    :procedure
    {:owner-authority "Consumer Financial Protection Bureau (CFPB)"
     :legal-basis "Truth in Lending Act / Real Estate Settlement Procedures Act, integrated at Regulation Z 12 CFR 1026.19(e) and (f) (TRID)"
     :provenance "https://www.consumerfinance.gov/rules-policy/regulations/1026/19/"
     :retrieved-at "2026-08-01"
     :scope "closed-end consumer credit transaction secured by real property or a cooperative unit, other than a reverse mortgage"
     :security-instrument "Mortgage or deed of trust, recorded at the county recorder (recording is per-county, not federal)"
     :steps ["Consumer submits an application to the creditor"
             "Creditor delivers or mails the Loan Estimate no later than three business days after receiving the consumer's application (1026.19(e))"
             "Underwriting and appraisal"
             "Consumer must receive the Closing Disclosure not later than three business days before consummation (1026.19(f))"
             "Consummation and county recording of the security instrument"]
     :cross-reference {:credit-side "cloud-itonami-isic-6492 credit.facts/catalog \"USA\""
                       :transfer-side "cloud-itonami-isic-6810 realty.facts/catalog \"USA-CA\" (per-county exemplar)"}}
    :support
    [{:id "usa.fha-203b"
      :name "FHA 203(b) Mortgage Insurance"
      :kind :government-mortgage-insurance
      :operator "Federal Housing Administration (FHA), U.S. Department of Housing and Urban Development (HUD)"
      :provenance "https://www.hud.gov/hud-partners/single-family-sfh203b"
      :retrieved-at "2026-08-01"
      :purpose "To provide mortgage insurance for a person to purchase or refinance a principal residence."
      :eligibility-signals
      ["Borrowers must meet standard FHA credit qualifications"
       "Approximately 96.5% financing available, with the upfront mortgage insurance premium financed into the loan"
       "Eligible properties are one-to-four unit structures"]
      :limits {:cy2026-floor-one-unit "$541,287"
               :cy2026-ceiling-one-unit "$1,249,125"
               :limits-provenance "https://www.hud.gov/news/hud-no-25-145"}
      :statutory-cite-note "The fetched HUD program page does NOT cite National Housing Act §203(b) or 24 CFR 203 -- those citations were not verified this session and are not asserted here."}]
    :organizations
    [{:id "cfpb" :name-en "Consumer Financial Protection Bureau" :name-local "Consumer Financial Protection Bureau"
      :role :conduct-regulator :url "https://www.consumerfinance.gov/" :isic "6419" :country "USA"}
     {:id "hud-fha" :name-en "Federal Housing Administration (HUD)" :name-local "Federal Housing Administration"
      :role :programme-operator :url "https://www.hud.gov/fha" :isic "6492" :country "USA"}]
    :verification
    {:fetched-this-session ["consumerfinance.gov Regulation Z 1026.19 (verbatim scope + both timing rules)"
                            "hud.gov 203(b) program page (verbatim)"
                            "hud.gov news release HUD No. 25-145 (CY2026 limits)"]
     :not-verified ["Fannie Mae / Freddie Mac / FHFA / VA / USDA programmes (not seeded at all)"
                    "state and local first-time buyer programmes (out of scope of this seed)"]}}

   "GBR"
   {:name "United Kingdom"
    :procedure
    {:owner-authority "Financial Conduct Authority (FCA) — regulated mortgage contracts; HM Land Registry — registration of the charge"
     :legal-basis "Financial Services and Markets Act 2000 (regulated mortgage contract) / FCA Handbook MCOB / Land Registration Act 2002"
     :provenance "https://www.handbook.fca.org.uk/handbook/MCOB/"
     :security-instrument "Legal charge, registered against the title at HM Land Registry"
     :cross-reference {:transfer-side "cloud-itonami-isic-6810 realty.facts/catalog \"GBR\""
                       :credit-side "cloud-itonami-isic-6492 credit.facts/catalog \"GBR\" (consumer credit, CONC -- NOT mortgages)"}
     :verbatim-rule-text-note "MCOB 11.6 (responsible lending / affordability) rule text could NOT be text-extracted this session -- the FCA Handbook site renders its rule text client-side. No MCOB rule wording is quoted here; only the Handbook entry point is cited."}
    :support
    [{:id "gbr.shared-ownership"
      :name "Shared Ownership (part-buy / part-rent)"
      :kind :part-equity-purchase
      :operator "Registered providers under Homes England programmes (the gov.uk eligibility page does not itself name the operating body)"
      :provenance "https://www.gov.uk/shared-ownership-scheme/who-can-apply"
      :retrieved-at "2026-08-01"
      :eligibility-signals
      ["your household income is £80,000 a year or less (£90,000 a year or less in London)"
       "you're a first-time buyer"
       "you used to own a home but cannot afford to buy one now"
       "you're forming a new household - for example, after a relationship breakdown"
       "you're an existing shared owner, and you want to move"
       "you own a home and want to move but cannot afford a new home that meets your needs"]
      :terms {:share "usually between 25% and 75%; a 10% share is available on some homes"
              :deposit "usually between 5% and 10% of the share you're buying"
              :older-persons "aged 55+: you can buy up to a 75% share through the Older Persons Shared Ownership (OPSO) scheme"}}
     {:id "gbr.mortgage-guarantee-scheme"
      :name "Mortgage Guarantee Scheme"
      :kind :state-guarantee
      :operator "HM Government"
      :provenance "https://www.gov.uk/government/news/government-extends-mortgage-guarantee-scheme"
      :retrieved-at "2026-08-01"
      :status :in-transition
      :status-note "The government has committed to launching a new, permanent, comprehensive mortgage guarantee scheme which will replace the existing scheme. Scheme details were stated as 'to be announced in due course' -- do not quote parameters of the successor scheme from this entry."}]
    :organizations
    [{:id "fca" :name-en "Financial Conduct Authority" :name-local "Financial Conduct Authority"
      :role :conduct-regulator :url "https://www.fca.org.uk/" :isic "6419" :country "GBR"}
     {:id "hm-land-registry" :name-en "HM Land Registry" :name-local "HM Land Registry"
      :role :land-registry :url "https://www.gov.uk/government/organisations/land-registry" :isic "6810" :country "GBR"}
     {:id "homes-england" :name-en "Homes England" :name-local "Homes England"
      :role :programme-operator :url "https://www.gov.uk/government/organisations/homes-england" :isic "6492" :country "GBR"}]
    :verification
    {:fetched-this-session ["gov.uk shared ownership eligibility page (verbatim)"
                            "gov.uk mortgage guarantee scheme news page (status only)"]
     :not-verified ["FCA MCOB rule text (site is client-side rendered)"
                    "Lifetime ISA, First Homes, Right to Buy (not seeded at all)"]}}

   "DEU"
   {:name "Germany"
    :procedure
    {:owner-authority "Grundbuchamt (Land Registry, local court); Bundesanstalt für Finanzdienstleistungsaufsicht (BaFin)"
     :legal-basis "BGB §491 ff. (Immobiliar-Verbraucherdarlehensvertrag) / BGB §311b (notarisation) / Grundbuchordnung"
     :provenance "https://www.gesetze-im-internet.de/bgb/__311b.html"
     :security-instrument "Grundschuld (or Hypothek), entered in Abteilung III of the Grundbuch"
     :steps ["Finanzierungsanfrage bei einem Finanzierungspartner (Bank, Sparkasse, Bausparkasse, Finanzvermittler, Versicherung)"
             "Kreditvertrag mit dem Finanzierungspartner"
             "Notarielle Beurkundung des Kaufvertrags (§311b BGB) und Bestellung der Grundschuld"
             "Eintragung im Grundbuch beim Grundbuchamt"]
     :cross-reference {:transfer-side "cloud-itonami-isic-6810 realty.facts/catalog \"DEU\""
                       :credit-side "cloud-itonami-isic-6492 credit.facts/catalog \"DEU\""}}
    :support
    [{:id "deu.kfw-300"
      :name "Wohneigentum für Familien – Neubau (KfW-Kredit Nr. 300)"
      :kind :subsidised-loan
      :operator "KfW Bankengruppe"
      :commissioned-by "Bundesministerium für Wohnen, Stadtentwicklung und Bauwesen"
      :provenance "https://www.kfw.de/PDF/Download-Center/F%C3%B6rderprogramme-(Inlandsf%C3%B6rderung)/PDF-Dokumente/6000005060_M_300_WEF.pdf"
      :source-edition "Merkblatt Kredit Nr. 300, Stand: 12/2025, Bestellnummer 600 000 5060"
      :retrieved-at "2026-08-01"
      :purpose "Die Förderung unterstützt Familien mit Kindern mit geringem oder mittlerem Einkommen beim Bau oder Erwerb von neuem selbstgenutztem und klimafreundlichem Wohneigentum in Deutschland."
      :eligibility-signals
      ["Natürliche Personen (Privatpersonen), die Eigentümerin oder Eigentümer von neu errichtetem, selbstgenutztem Wohneigentum werden"
       "bei denen mindestens ein Kind im Haushalt lebt, welches das 18. Lebensjahr noch nicht vollendet hat"
       "deren zu versteuerndes jährliches Haushaltseinkommen 90.000 Euro bei einem Kind, zuzüglich 10.000 Euro je weiterem Kind nicht überschreitet und"
       "die bei Antragstellung über kein Wohneigentum verfügen"
       "Nicht antragsberechtigt sind natürliche Personen, die in einer Gesellschaft bürgerlichen Rechts zusammengeschlossen sind."]
      :terms {:amount "zinsgünstiger Kredit, der abhängig von der Förderstufe und der Anzahl der Kinder zwischen 170.000 und 270.000 Euro beträgt"
              :funding-levels ["Klimafreundliches Wohngebäude (Effizienzhaus 40 + Treibhausgasemissionen im Gebäudelebenszyklus)"
                               "Klimafreundliches Wohngebäude – mit QNG"]
              :self-use "Die geförderte Wohneinheit ist als (Mit)Eigentümerin oder (Mit)Eigentümer mindestens fünf Jahre ab Einzug selbst zu nutzen."
              :ownership-share "Der Eigentumsanteil an der geförderten Wohnimmobilie muss mindestens 50 Prozent betragen."
              :once-only "Antragstellende können nur einmal eine Förderung aus diesem Produkt erhalten."}
      :steps ["Expertin oder Experte für Energieeffizienz einbinden — prüft und bestätigt die Einhaltung der technischen Anforderungen und erstellt die \"Bestätigung zum Antrag\""
              "Kredit beantragen und erhalten — der Kredit ist vor Beginn des Vorhabens bei einem Finanzierungspartner zu beantragen"
              "Vorhaben durchführen und nachweisen — der Abschluss des Vorhabens ist nach Einzug dem Finanzierungspartner nachzuweisen"]
      :evidence ["Einkommensteuerbescheide des Finanzamtes (Durchschnitt des zweiten und dritten Jahres vor Antragstellung)"
                 "Grundbuchauszug für die geförderte neue Wohnimmobilie (Nachweis des Eigentumsanteils)"]
      :sibling-programme-note "Ein Schwesterprodukt für den Bestandserwerb existiert (KfW 308, \"Jung kauft Alt\"); dessen Konditionen wurden in dieser Sitzung NICHT verifiziert und sind hier nicht abgebildet."}]
    :organizations
    [{:id "kfw" :name-en "KfW Banking Group" :name-local "KfW Bankengruppe"
      :role :programme-operator :url "https://www.kfw.de/" :isic "6492" :country "DEU"}
     {:id "bmwsb" :name-en "Federal Ministry for Housing, Urban Development and Building"
      :name-local "Bundesministerium für Wohnen, Stadtentwicklung und Bauwesen"
      :role :programme-principal :url "https://www.bmwsb.bund.de/" :isic "8411" :country "DEU"}
     {:id "bafin" :name-en "Federal Financial Supervisory Authority"
      :name-local "Bundesanstalt für Finanzdienstleistungsaufsicht"
      :role :prudential-regulator :url "https://www.bafin.de/" :isic "6419" :country "DEU"}]
    :verification
    {:fetched-this-session ["KfW Merkblatt Kredit Nr. 300 PDF, Stand 12/2025 (verbatim, pages 1-3)"]
     :not-verified ["KfW 308 Bestandserwerb conditions"
                    "Länder-level Förderbanken (NRW.BANK, L-Bank etc.) — not seeded at all"]}}

   "FRA"
   {:name "France"
    :procedure
    {:owner-authority "État / établissements de crédit ayant signé une convention avec l'État"
     :legal-basis "Code de la consommation (crédit immobilier) — the specific article range was NOT verified this session"
     :provenance "https://www.service-public.gouv.fr/particuliers/vosdroits/F10871"
     :security-instrument "Hypothèque or privilège de prêteur de deniers, established by acte notarié"
     :cross-reference {:credit-side "not seeded in cloud-itonami-isic-6492 (FRA absent from that catalog)"}
     :verbatim-rule-text-note "Only the PTZ page was fetched. The general French mortgage procedure (offre de prêt, délai de réflexion de 10 jours, durée de validité) was NOT verified this session and is deliberately not asserted."}
    :support
    [{:id "fra.ptz"
      :name "Prêt à taux zéro (PTZ)"
      :kind :interest-free-state-loan
      :operator "État, distribué par les établissements de crédit conventionnés"
      :provenance "https://www.service-public.gouv.fr/particuliers/vosdroits/F10871"
      :retrieved-at "2026-08-01"
      :purpose "C'est un prêt aidé par l'État. Vous devez rembourser le montant qui vous est prêté, mais vous n'avez pas à payer d'intérêts."
      :eligibility-signals
      ["Vous ne devez pas avoir été propriétaire de votre résidence principale au cours des 2 années précédant le PTZ"
       "Des exceptions existent (usufruitier, bénéficiaire de l'AAH/AEEH, logement détruit par une catastrophe naturelle)"
       "Plafonds de ressources variables selon la zone et le nombre de personnes du foyer (exemples relevés: zone A, 2 personnes: 73 500 € ; zone C, 2 personnes: 42 750 €)"]
      :eligible-property ["Logement neuf (moins de 5 ans à la première occupation)"
                          "Logement ancien avec travaux d'amélioration/énergétiques représentant au moins 25 % du coût total"
                          "Achat d'un logement social"
                          "Transformation d'un local en logement"]
      :figures-note "The plafonds de ressources quoted above are the two examples the fetched page surfaced, not the full grid. Read the source grid before applying any ceiling."}]
    :organizations
    [{:id "service-public-fr" :name-en "French public service administration portal (État)"
      :name-local "Service-Public.fr / État"
      :role :programme-principal :url "https://www.service-public.gouv.fr/" :isic "8411" :country "FRA"}]
    :verification
    {:fetched-this-session ["service-public.gouv.fr PTZ page F10871 (verbatim)"]
     :not-verified ["Code de la construction et de l'habitation article citation for the PTZ"
                    "prêt d'accession sociale (PAS), prêt Action Logement, ANIL — not seeded at all"
                    "Banque de France / ACPR role in mortgage conduct supervision"]}}

   "NLD"
   {:name "Netherlands"
    :procedure
    {:owner-authority "Kadaster (openbare registers); Autoriteit Financiële Markten (AFM) — hypothecair krediet"
     :legal-basis "Burgerlijk Wetboek Boek 3 art. 260 (hypotheekakte) / Wet op het financieel toezicht"
     :provenance "https://www.kadaster.nl/"
     :security-instrument "Hypotheekrecht, vested by notarial deed and registered in the openbare registers of Kadaster"
     :steps ["Hypotheekadvies en -aanvraag bij een geldverstrekker of adviseur"
             "Bindend aanbod en (optioneel) NHG-toets"
             "Passeren van de leveringsakte en de hypotheekakte bij de notaris"
             "Inschrijving van beide akten bij het Kadaster"]
     :cross-reference {:transfer-side "cloud-itonami-isic-6810 realty.facts/catalog \"NLD\" (the richest sibling entry: Kadaster search, Wwft, erfpacht, VvE)"}
     :verbatim-rule-text-note "The BW art. 260 and Wft citations here were NOT re-verified against wetten.overheid.nl in this session; the NLD entry in isic-6810 (which WAS source-verified) is the authority for the transfer side."}
    :support
    [{:id "nld.nhg"
      :name "Nationale Hypotheek Garantie (NHG)"
      :kind :state-backed-guarantee
      :operator "Stichting Waarborgfonds Eigen Woningen (WEW)"
      :provenance "https://www.nhg.nl/nhg-actueel/nhg-grens-in-2026-vastgesteld-op-470000/"
      :retrieved-at "2026-08-01"
      :purpose "Nationale Hypotheek Garantie (NHG) zet zich in om verantwoorde woonfinanciering voor meer consumenten mogelijk te maken."
      :eligibility-signals
      ["De NHG-grens per 1 januari 2026 is € 470.000"
       "Bij meefinanciering van energiebesparende voorzieningen is de grens € 498.200"
       "De borgtocht geldt voor de looptijd van de lening, maar maximaal 30 jaar"]
      :terms {:borgtochtprovisie "0,4% van het geleende bedrag (eenmalig, 2026)"
              :grens-methodiek "Sinds 2023 wordt de NHG-grens bepaald op basis van de gemiddelde koopsom van de afgelopen 27 maanden, verhoogd met 5% en afgerond op een bedrag deelbaar door € 5.000"}
      :achtervang-note "NHG's waarborgfondsconstructie involves an achtervang relationship between WEW and the Rijk/gemeenten. The specifics of that construction were NOT text-extracted this session -- see https://www.nhg.nl/over-nhg/waarborgfondsconstructie/ before describing it."}]
    :organizations
    [{:id "wew-nhg" :name-en "Homeownership Guarantee Fund Foundation (NHG)"
      :name-local "Stichting Waarborgfonds Eigen Woningen"
      :role :programme-operator :url "https://www.nhg.nl/" :isic "6492" :country "NLD"}
     {:id "afm" :name-en "Dutch Authority for the Financial Markets"
      :name-local "Autoriteit Financiële Markten"
      :role :conduct-regulator :url "https://www.afm.nl/" :isic "6419" :country "NLD"}
     {:id "kadaster" :name-en "Netherlands' Cadastre, Land Registry and Mapping Agency"
      :name-local "Kadaster"
      :role :land-registry :url "https://www.kadaster.nl/" :isic "6810" :country "NLD"}]
    :verification
    {:fetched-this-session ["nhg.nl over-nhg page (purpose + 30-year cap, verbatim)"
                            "nhg.nl NHG-grens 2026 announcement (limits, provisie, methodiek)"]
     :not-verified ["waarborgfondsconstructie / achtervang detail"
                    "Voorwaarden en Normen 2026-1 document contents"
                    "starterslening (SVn) — not seeded at all"]}}})

(defn spec-basis
  "The jurisdiction's entry, or nil -- nil means NO spec-basis in this catalog,
  never a default. Callers must treat nil as uncovered, not as permissive."
  [iso3]
  (get catalog iso3))

(defn support-programmes
  "Public support programmes seeded for `iso3`, or [] when the jurisdiction is
  not covered. Empty is NOT evidence that the jurisdiction operates none."
  [iso3]
  (vec (:support (spec-basis iso3))))

(defn organizations
  "Organizations seeded for `iso3` across the procedure and support planes."
  [iso3]
  (vec (:organizations (spec-basis iso3))))

(defn all-organizations
  "Every seeded organization across all covered jurisdictions, for the
  organization plane on its own. Joinable to cloud-itonami-assoc-* catalogs on
  (:isic, :country)."
  []
  (vec (mapcat :organizations (vals catalog))))

(defn coverage
  "Honest coverage report over `iso3s`: which are actually seeded and which are
  not. Never reports a missing jurisdiction as covered, and never reports a
  seeded jurisdiction as complete -- see each entry's `:verification`."
  [iso3s]
  (let [have (filter spec-basis iso3s)
        missing (remove spec-basis iso3s)]
    {:requested (vec iso3s)
     :covered-jurisdictions (vec (sort have))
     :missing-jurisdictions (vec (sort missing))
     :note (str (count have) " of " (count iso3s)
                " jurisdictions seeded with an official spec-basis. "
                "This catalog covers " (count catalog)
                " jurisdictions in total, out of roughly 194 -- extend "
                "`mortgage.facts/catalog` from official sources, never "
                "fabricate a jurisdiction's procedure, programme figures or "
                "eligibility ceilings.")}))

(defn unverified-claims
  "Every `:not-verified` item across the catalog, flattened. This is the
  catalog's own to-do list: it exists so a reader can never mistake seeded
  coverage for complete coverage."
  []
  (vec (for [[iso3 entry] catalog
             item (get-in entry [:verification :not-verified])]
         {:jurisdiction iso3 :unverified item})))

(defn summary
  "One-line-per-jurisdiction summary, for a README or an operator console."
  []
  (->> catalog
       (map (fn [[iso3 {:keys [name support organizations]}]]
              (str iso3 " (" name "): "
                   (count support) " support programme(s), "
                   (count organizations) " organization(s) -- "
                   (str/join ", " (map :name support)))))
       sort
       vec))
