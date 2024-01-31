//******************************************************************************
// Groovy code for HE by trs56, yours to take and use as you please :)
//******************************************************************************
// This is the near final batch of code for the Alarm Server I am building
// into my Hikvision Camera Controller for Hubitat Elevation.
//
// This snippet is only for the parse method that processes incoming event
// alert messages from the camera via http on hub port 39501, routed to
// the device by its DNI (hex ip), only to the point of determining the
// event type.
//
// The code shows you how to parse both the XML and multi-part alert messages
// now being sent by newer cameras. Why Hikvision did not keep the status quo
// with XML is beyond me. But if you look closely, there in the multi-part message
// is the exact same XML, which you can easily "search", as you will see below.
// The key is knowing what you got, and that's easy, too.
//******************************************************************************
import groovy.transform.Field // Needed to use @Field static constant lists/maps
//******************************************************************************
// PARSE - PARSE - PARSE - HIKVISION ALARM SERVER
//******************************************************************************
void parse(String description) {
    String etype = ""    // eventType
    String estate = ""   // eventState
    Boolean ok = false   // Only Supported Motion Events trigger motion on this device
    Boolean ns = false   // Not Supported and Unknown Events are logged and ignored

    def rawmsg = parseLanMessage(description)   
    def hdrs = rawmsg.headers  // its a map
    def msg = ""               // tbd
    if (debuga) {hdrs.each {log.debug it}}
    // This is the key to knowing what you have to work with
    if (hdrs["Content-Type"] == "application/xml; charset=\"UTF-8\"") {
        msg = new XmlSlurper().parseText(new String(rawmsg.body))
        if (debuga) {
            log.debug "EOM********************************************************"
            log.debug groovy.xml.XmlUtil.escapeXml(rawmsg.body)
            log.debug "XML EVENT MESSAGE******************************************"
        }
        estate = msg.eventState.text()
        etype = msg.eventType.text()
        if (debuga) {log.debug "eventType: " + etype}
        if (debuga) {log.debug "eventState: " + estate}
        etype = ">" + etype + "<"
        for (event in SupportedEvents) {
            if (etype == event) {
                ok = true
                break}
        }
        for (event in UnsupportedEvents) {
            if (etype == event) {
                ns = true
                break}
        }
        etype = etype.substring(1,etype.length()-1)
    } else {
        msg = rawmsg.body.toString()
        if (debuga) {
            log.debug "EOM***********************************************************"
            log.debug msg
            log.debug "MULTI-PART EVENT MSG******************************************"
        }
        for (event in SupportedEvents) {
            if (msg.contains("$event")) {
                etype = event
                ok = true
                break}
        }
        for (event in UnsupportedEvents) {
            if (msg.contains("$event")) {
                etype = event
                ns = true
                break}
        }        etype = etype.substring(1,etype.length()-1)
    }
    if (!ok && !ns) {
        etype = "Unknown"
        ns = true
    }
    // Translate to user/driver friendly names
    etype = TranslateEvents."$etype"
// **************************************************************    
// You take it from here
//*****************************************************************
    
}
//*****************************************************************
// Static Constants
//*****************************************************************
@Field static List SupportedEvents = [
    ">IO<",
    ">VMD<",
    ">PIR<",
    ">linedetection<",
    ">fielddetection<",
    ">regionEntrance<",
    ">regionExiting<",
    ">attendedBaggage<",
    ">unattendedBaggage<"
]
@Field static List UnsupportedEvents = [
    ">facedetection<",
    ">faceSnap<",
    ">loitering<",
    ">scenechangedetection<",
    ">storageDetection<",
    ">badvideo<",
    ">diskerror<",
    ">diskfull<",
    ">illAccess<",
    ">ipconflict<",
    ">nicbroken<",
    ">tamperdetection<",
    ">videomismatch<"
]
@Field static Map TranslateEvents = [
    IO:"AlarmIn",
    VMD:"Motion",
    PIR:"PIR",
    linedetection:"LineCross",
    fielddetection:"Intrusion",
    attendedBaggage:"ObjectR",
    regionEntrance:"RgnEnter",
    regionExiting:"RgnExit",
    unattendedBaggage:"UBaggage",
    // unsupported
    facedetection:"FaceD",
    faceSnap:"FaceSnap",
    loitering:"Loitering",
    scenechangedetection:"SceneChg",
    storageDetection:"Storage",
    badvideo:"BadVideo",
    diskerror:"DiskError",
    diskfull:"DiskFull",
    illAccess:"LoginFailed",
    tamperdetection:"Tamper",
    Unknown:"Unknown"
]
