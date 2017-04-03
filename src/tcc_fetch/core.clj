(ns tcc-fetch.core
  (:gen-class)
  (:require
    [ardoq.analytics-clj :as segment]
    [cheshire.core :as json]
    [clojure.edn :as edn]
    [clojure.java.io :as jio]
    [clojure.string :as s]
    [clojure.tools.logging :as log]
    [clojure.xml :as xml]
    [org.httpkit.client :as http]))

(def base-uri "https://tccna.honeywell.com/ws/MobileV2.asmx/")
(def librato-uri "https://metrics-api.librato.com/v1/metrics")
(def sleep-time-ms 60000)
(def min-sleep-time-ms 100)

(defn mobile-request
  [endpoint form-params]
  @(http/post (str base-uri endpoint)
              {:form-params form-params}))

(defn to-xml
  [response]
  (-> response :body .getBytes java.io.ByteArrayInputStream. xml/parse))

(defn xtags
  [xml tag]
  (->> xml :content (filter (fn [e] (= (:tag e) tag)))))

(defn xml-value
  [xml tag]
  (-> xml (xtags tag)
      first :content first))

(defn xml-float
  [xml tag]
  (Float/parseFloat (xml-value xml tag)))

(defn xml-map
  [xml]
  (let [content (:content xml)]
    (zipmap (map :tag content) (map :content content))))

(defn parse-thermostat
  [t]
  (let [temp-keys [:DispTemperature :HeatSetpoint :StatusHeat]
        device-name (xml-value t :UserDefinedDeviceName)
        status (xml-value t :EquipmentStatus)
        ui (first (xtags t :UI))
        fan-running (-> t (xtags :Fan) first (xml-value :IsFanRunning) Boolean/parseBoolean)
        temp-values (zipmap temp-keys (map #(xml-float ui %) temp-keys))]
    (assoc temp-values
           :UserDefinedDeviceName device-name
           :EquipmentStatus status
           :IsFanRunning fan-running)))

(defn parse-weather
  [current-weather]
  (let [cw (xml-map current-weather)]
    {:Temperature (-> cw :Temperature first Float/parseFloat (* 1.8) (+ 32.0))
     :Humidity (-> cw :Humidity first Float/parseFloat)}))

(defn parse-locations
  [loc-xml]
  (if (not (= "Success" (xml-value loc-xml :Result)))
    (throw (RuntimeException. (str "Unable to parse locations: " loc-xml)))
    (let [locs (xml-value loc-xml :Locations)
          current-weather (-> locs (xtags :CurrentWeather) first)
          parsed-weather  (parse-weather current-weather)
          thermostats     (-> locs (xtags :Thermostats) first :content)
          parsed-tstats   (mapv parse-thermostat thermostats)]
      {:time-ms (System/currentTimeMillis)
       :weather parsed-weather
       :thermostats parsed-tstats})))

(def session-id (atom ""))

(defn do-login
  [auth-params]
  (log/info "Calling AuthenticateUserLogin")
  (let [login (to-xml (mobile-request "AuthenticateUserLogin" auth-params))]
    (if (-> login (xml-value :Result) (= "Success") not)
      (throw (RuntimeException. (str "Unable to login: " login)))
      (let [sid (xml-value login :SessionID)]
        (reset! session-id sid)
        sid))))


(defn get-locations
  [auth-params]
  (let [get-loc (fn [] (to-xml (mobile-request "GetLocations" {:sessionID @session-id})))
        locations (get-loc)]
    (if (-> locations (xml-value :Result) (= "InvalidSessionID"))
      (do
        (do-login auth-params)
        (get-loc))
      locations)))

(defn read-temps
  [auth-params]
  (parse-locations (get-locations auth-params)))

(defn temps-to-metrics
  [temps]
  {:measure_time (-> temps :time-ms (quot 1000))
   :gauges
   (concat
     [{:source "Weather" :name "temperature" :value (-> temps :weather :Temperature)}
      {:source "Weather" :name "humidity" :value (-> temps :weather :Humidity)}]
     (->> temps
          :thermostats
          (map (fn [t]
                 (let [base {:source (s/replace (:UserDefinedDeviceName t) " " "_")}]
                   (map #(merge base %)
                        [{:name "temperature" :value (:DispTemperature t)}
                         {:name "heating" :value (if (= "Heating" (:EquipmentStatus t)) 1 0)}
                         {:name "fan_running" :value (if (:IsFanRunning t) 1 0)}]))))
          flatten))})

(defn post-metrics
  [auth metrics]
  @(http/post
     librato-uri
     (merge auth
            {:headers {"Content-type" "application/json"}
             :body (json/generate-string metrics)})))

(defn send-event
  [segment-client user-id temps]
  (segment/track segment-client user-id "Thermostat Read" temps))

(defn run-loop
  [{:keys [tcc-auth librato-auth segment-auth]}]
  (let [segment-client (segment/initialize (:write-key segment-auth))
        user-id (:user-id segment-auth)]
    (loop []
      (let [t0 (System/currentTimeMillis)]
        (try
          (let [temps (read-temps tcc-auth)
                metrics (temps-to-metrics temps)]
            (log/info temps)
            (send-event segment-client user-id temps)
            (comment (post-metrics librato-auth metrics)))
          (catch Throwable t
            (log/error t "Error")))
        (let [dt (- (System/currentTimeMillis) t0)]
          (Thread/sleep (max min-sleep-time-ms (- sleep-time-ms dt)))
          (recur))))))

(defn load-config
  [config-file]
  (log/info "Loading config from file: " config-file)
  (with-open [in (java.io.PushbackReader. (jio/reader config-file))]
    (edn/read in)))

(defn -main
  [& args]
  (if (not (= 1 (count args)))
    (println "Usage: cmd <config.clj>")
    (run-loop (load-config (first args)))))

(comment
  (require '[clojure.pprint :as pprint])
         )
