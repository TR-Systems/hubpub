//******************************************************************************
//* Hikvision Camera Controller - Device Driver for Hubitat Elevation
//******************************************************************************
// Copyright 2024 Thomas R Schmidt, Wildwood IL
//******************************************************************************
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//******************************************************************************
// This driver allows you to trigger Alarm Input events on the camera and
// enable/disable Motion Detection features by running its custom commands
// from your rules and apps. It also acts as a Motion Sensor in HE if your
// camera has the Alarm Server/HTTP Listening feature available.
//
// The User Guide is required reading.
// https://tr-systems.github.io/web/HCC_UserGuide.html
// Contact for support: trsystems.help at the little G mail place.
//******************************************************************************
// Change Log
// Date        Version  Release Notes
// 01-26-2024  1.0.0    First Release: Please refer to the User Guide
//
//******************************************************************************
import groovy.transform.Field // Needed to use @Field static lists/maps
//******************************************************************************
metadata {
    definition (name: "Hikvision Camera Controller", 
                author: "Thomas R Schmidt", namespace: "tr-systems", // github userid
                singleThreaded: true) // 
    {
        capability "Actuator"
        capability "Switch"
        capability "MotionSensor"

        command "on" , [[name:"Trigger Alarm"]]
        command "off" , [[name:"Clear Alarm"]]
        command "Enable", [[name:"Features",type:"STRING",description:"Features: m.p.in.lc.rx.re.or.ub.ai"]]
        command "Disable", [[name:"Features",type:"STRING",description:"Features: m.p.in.lc.rx.re.or.ub.ai"]]
        
        attribute "AlarmInH", "STRING"   // Enabled/Disabled State of Alarm Input Handler
        attribute "AlarmIn", "STRING"    // Active/Inactive State of Alarm Input Port
        attribute "AlarmOut", "STRING"   // "
        attribute "Intrusion", "STRING"  // Enabled/Disabled State of Feature"
        attribute "LineCross", "STRING"  // ""
        attribute "MotionD", "STRING"    // ""
        attribute "ObjectR", "STRING"    // ""
        attribute "PIRSensor", "STRING"  // ""
        attribute "RgnEnter", "STRING"   // ""
        attribute "RgnExit", "STRING"    // ""
        attribute "UBaggage", "STRING"   // ""
        attribute "motion", "STRING"     // active/inactive
        attribute "zDriver", "STRING"    // State of this device in HE: OK, ERR, OFF, CRED
        // OK = Everything is groovy
        // ERR = Unexpected get/put errors occurred.
        // OFF = Camera is offline
        // CRED = Authentication failed, credentials on the camera have changed
        // FAILED = Only during camera validation when saving preferences
	}
    preferences 
    {
        input(name: "devIP", type: "string", 
              description: " ",
              title:"Camera or NVR IP Address",
              required: true)
        input(name: "devPort", type: "string",
              title:"Camera or NVR Virtual HTTP Port",
              description: " ",
              defaultValue: "80",
              required: true)
        input(name: "devCred", type: "password",
              title:"Credentials for Login",
              description: "userid:password",
              required: true)
        input(name: "devName", type: "string",
              title:"Camera Name for Verification",
              description: " ",
              required: true)
        input(name: "devMotionReset", type: "number",
              title:"Reset Interval for Motion Detection",
              description: "(From 1 to 20 minutes)",
              range: "1..20",
              devaultValue: "4",
              required: true)
        input(name: "devResetCounters", type: "enum",
              title:"Reset Alarm Server Event Counters",
              description: "Select frequency",
              options: ["Daily","Weekly","Monthly","Only on Save"],
              defaultValue: "Monthly",
              required: true)
        input(name: "devExclude", type: "string",
              title:"Features to Exclude from Driver Control",
              description: "List: m.p.in.lc.rx.re.or.ub.ai.ao",
              required: false)
        input(name: "debug", type: "bool",
              title: "Debug logging for Controller",
              description: "(resets in 30 minutes)",
              defaultValue: false)
        input(name: "debuga", type: "bool",
              title: "Debug logging for Alarm Server",
              description: "(resets in 30 minutes)",
              defaultValue: false)
    }
}
//******************************************************************************
String strMsg = " " // Used to pass status (OK or errmsg) back from
//                     SendGet/Put Requests for logging and program
//                     control in the calling methods
//******************************************************************************
// INSTALLED - INSTALLED - INSTALLED - Installing New Camera Device
//******************************************************************************
void installed() {
    def l = []
    l << "IMPORTANT: Please do not Save Preferences until your camera has"
    l << "been configured to operate with this driver. The information"
    l << "you need to do that can be found here:"
    l << " "
    l << "https://tr-systems.github.io/web/HCC_UserGuide.html"
    l << " "
    l << "Thank you. You may now hand over your credentials and proceed."
    def lr = l.reverse()
    lr.each {log.info it}
    sendEvent(name:"zDriver",
              value:"Hello! First camera? PLEASE OPEN THE LOG NOW. If not, please proceed.")

    device.setName(device.getLabel())
}
//******************************************************************************
// UPDATED - UPDATED - UPDATED - Preferences Saved
//******************************************************************************
void updated() {
    String errcd = ""
    String dni = ""
    log.warn "Saving Preferences and validating new camera"
    if (devCred.length() > 6 && devCred.substring(0,6) == "admin:") {
        strMsg = "Hikvision admin account not allowed. Use Operator account with Remote Parmameters/Settings and Remote Notify options selected."
        log.error strMsg
        sendEvent(name:"zDriver",value:"FAILED")
        return
    }
    unschedule()    
    state.clear()
    // Required settings won't be null
    devIP = devIP.trim()
    devPort = devPort.trim()
    devName = devName.trim()
    devCred = devCred.trim()
    device.updateSetting("devIP", [value:"${devIP}", type:"string"])
    device.updateSetting("devPort", [value:"${devPort}", type:"string"])
    device.updateSetting("devCred", [value:"${devCred}", type:"string"])
    device.updateSetting("devName", [value:"${devName}", type:"string"])
    // Remove all Data fields
    device.removeDataValue("Name")
    device.removeDataValue("Model")
    device.removeDataValue("Firmware")

    // Save the new credentials
    device.updateDataValue("CamID",devCred.bytes.encodeBase64().toString())

    // Be safe by making it a long
    long port = devPort.toInteger()
    if (port < 65001) {
        log.info "Pinging IP: " + devIP
    } else {
        log.info "Pinging NVR: " + devIP
    }
    // Start validating
    if (!PingOK(devIP)) {
        if (port < 65001) {
            strMsg = "Ping failed, Bad IP or no route to subnet"
        } else {
            strMsg = "Ping failed, NVR is offline"
        }
        log.error strMsg
        sendEvent(name:"zDriver",value:"FAILED")
        return
    }
    errcd = GetCameraInfo()
    if (errcd != "OK") {
        sendEvent(name:"zDriver",value:"FAILED")
        return
    }
    // Get and compare the name added/updated by GetCamerInfo
    String cname = device.getDataValue("Name")
    if (cname == null) {cname = ""}
    if (cname == "") {cname = "(no name)"}
    if (cname != devName) {
        log.error "Name mis-match, Camera returned: " + cname
        sendEvent(name:"zDriver",value:"FAILED")
        return
    }
    // Validate the Operator account
    errcd = GetUserInfo()
    if (errcd != "OK") {
        sendEvent(name:"zDriver",value:"FAILED")
        return
    }
    errcd = GetAlarmServerInfo()
    // Returns OK (is configured), NA (not configured or not available, can't tell which)
    // or ERR w/strMsg = error message (GET error or incorrectly configured)
    // state.AlarmSvr is set to either OK or NA and used below.
    if (errcd == "ERR" || errcd == "CRED") {
        sendEvent(name:"zDriver",value:"FAILED")
        return
    }
    if (state.AlarmSvr == "OK") {
        state.LastAlarm = "na"
        state.AlarmCount = 0
        state.LastMotionEvent = "na"
        state.LastMotionTime = "na"
        state.MotionEventCount = 0
        state.OtherEventState = "inactive"
        state.LastOtherEvent = "na"
        state.LastOtherTime = "na"
        state.OtherEventCount = 0
        state.EventMsgCount = 0
    }
    // If using NVR Virtual Host, we need to get the subnet ip address for the dni
    // and make sure the default gateway is set if the alarm server is configured.
    if (port >= 65001) {
        errcd = GetSubnetIP()
        // Returns OK or NOGW with strMsg=subnet ip, or GET ERR w/strMsg = error message
        if (errcd == "ERR" || errcd == "CRED") {
            sendEvent(name:"zDriver",value:"FAILED")
            return    
        }
        if (state.AlarmSvr == "OK" && errcd == "NOGW") {
            log.error "2) Establish NVR Subnet routing per the User Guide"
            log.error "1) Remove Alarm Server from camera configuration, OR:"
            log.error "Alarm Server is configured without a Default Gateway for routing."
            state.AlarmSvr = "NA"
            sendEvent(name:"zDriver",value:"FAILED")
            return
        }
        dni = strMsg
        log.info "Using NVR subnet ip for DNI: " + strMsg
    } else {
        dni = devIP
    }
    dni = dni.tokenize(".").collect {String.format( "%02x", it.toInteger() ) }.join()
    dni = dni.toUpperCase()
    log.info "Attempting to register Device Network ID: " + dni
    try {device.deviceNetworkId = "${dni}"
    } catch (Exception e) {
        log.error e.message
        log.error "Required DNI \"$dni\" not unique. Another device with the same IP Address exists on the hub."
        sendEvent(name:"zDriver",value:"FAILED")
        return
    }
    log.info "DNI registered"

    errcd = GetSetStates()
    // Returns OK, NA w/StrMsg=new exclude filter, or ERR/CRED w/strMsg=error message
    if (errcd == "ERR" || errcd == "CRED") {
        sendEvent(name:"zDriver",value:"FAILED")
        return
    }
    // Add features not found to the Exclude filter
    if (errcd == "NA") {
        strMsg = strMsg + devExclude
        log.warn "Setting new Exclude Filter:" + strMsg
        device.updateSetting("devExclude", [value:"${strMsg}", type:"string"])
    }
    if (devResetCounters == "Daily") {schedule('0 0 1 * * ?',ResetCounters)}
    if (devResetCounters == "Weekly") {schedule('0 0 1 ? * 1',ResetCounters)}
    if (devResetCounters == "Monthly") {schedule('0 0 1 1 * ?',ResetCounters)}
    if (debug) {runIn(1800, ResetDebugLogging, overwrite)}
    
    log.warn "Camera $cname validated, ready for operation"
    sendEvent(name:"zDriver",value:"OK")
}
//******************************************************************************
void ResetDebugLogging() {
    log.info "Debug logging is off"
    device.updateSetting("debug", [value:false, type:"bool"])
    device.updateSetting("debuga", [value:false, type:"bool"])
}
void ResetCounters() {
    log.warn "Resetting Alarm Server Counters"
    state.AlarmCount = 0
    state.MotionEventCount = 0
    state.OtherEventCount = 0
    state.EventMsgCount = 0
}
//******************************************************************************
// GET CAMERA INFO - GET CAMERA INFO - GET CAMERA INFO - GET CAMERA INFO
//******************************************************************************
private GetCameraInfo() {
    String errcd = ""
    log.info "GET: http://" + devIP + ":" + devPort + FeaturePaths.SysInfo
    //  If the response from the GET request is successful, the xml returned
    //  will be presented in the format requested and strMsg will be "OK".
    //  Otherwise, xml will be null and strMsg will contain the error message.
    //  Further analysis of GET errors is then performed by LogGETError.
    //  This applies to all calls to the SendGet and SendPut Request methods.
    //  Don't mess with strMsg unless you know what you're doing.
    strMsg = ""
    def xml = SendGetRequest(FeaturePaths.SysInfo,"GPATH")
    if (strMsg != "OK") {
        errcd = LogGETError()
        return(errcd)
    }
    log.info "Device Type: " + xml.deviceType.text()
    log.info "Name: " + xml.deviceName.text()
    log.info "Model: " + xml.model.text()
    log.info "Firmware: " + xml.firmwareVersion.text() + " " + xml.firmwareReleasedDate.text()

    if (xml.deviceType.text() == "NVR" || xml.deviceType.text() == "DVR") {
        strMsg = "You have connected to a Hikvision NVR/DVR, Use the Virtual Host Port or POE Subnet address to access the camera"
        log.error strMsg
        return("ERR")
    }
    device.updateDataValue("Name",xml.deviceName.text())
    device.updateDataValue("Model",xml.model.text())
    device.updateDataValue("Firmware",xml.firmwareVersion.text() + " " + xml.firmwareReleasedDate.text())
    return("OK")
}
//******************************************************************************
// GET USER INFO - GET USER INFO - GET USER INFO - GET USER INFO - GET USER INFO
//******************************************************************************
private GetUserInfo() {
    String errcd = ""
    log.info "Validating Operator account"
    if (debug) {log.debug "GET: " + FeaturePaths.CamUsers}
    // This GET will only return the user being used, not the entire list
    // Only the admin account gets the entire list of users
    // So glad it does this because now its easy to get the user id for the next step
    xml = SendGetRequest(FeaturePaths.CamUsers,"GPATH")
    if (strMsg != "OK") {
        errcd = LogGETError()
        return(errcd)
    }
    String userid = xml.User.id.text()
    log.info "UserID: " + userid
    log.info "UserLevel: " + xml.User.userLevel.text()
    if (xml.User.userLevel.text() != "Operator") {
        strMsg = "User account on camera is not an Operator"
        log.warn strMsg
        return("ERR")
    }
    String path = FeaturePaths.UserPerm + userid
    if (debug) {log.debug "GET: " + path}
    // Get User Permissions
    xml = SendGetRequest(path,"GPATH")
    if (strMsg != "OK") {
        errcd = LogGETError()
        return(errcd)
    }
    if (xml.remotePermission.parameterConfig.text() != "true" || xml.remotePermission.alarmOutOrUpload.text() != "true") {
        strMsg = "Operator account on camera must have both Remote Parameters and Remote Notify options selected."
        log.error strMsg
        return("ERR")
    }
    log.info "Operator account validated"
    return("OK")
}
//******************************************************************************
// GET SUBNET IP - GET SUBNET IP - GET SUBNET IP - GET SUBNET IP - GET SUBNET IP
//******************************************************************************
private GetSubnetIP() {
    String errcd = ""
    String gwaddr = ""
    String ipaddr = ""
    // Get Interfaces/1 (making the assumption its connected on 1)
    log.info "Checking Network Configuration"
    if (debug) {log.info "GET: " + FeaturePaths.Network}
    xml = SendGetRequest(FeaturePaths.Network,"GPATH")
    if (strMsg != "OK") {
        errcd = LogGETError()
        return(errcd)
    }
    ipaddr = xml.IPAddress.ipAddress.text()
    gwaddr = xml.IPAddress.DefaultGateway.ipAddress.text()
    log.info "Camera ipAddress: " + ipaddr
    log.info "Default Gateway: " + gwaddr
    if (gwaddr == "0.0.0.0") {
        log.warn "Default Gateway for the NVR subnet is not defined on the camera."
        errcd = "NOGW"
    } else {
        errcd = "OK"
    }
    strMsg = ipaddr
    return(errcd)
}
//******************************************************************************
// GET ALARM SERVER INFO - GET ALARM SERVER INFO - GET ALARM SERVER INFO
//******************************************************************************
// Return errcd OK, NA or ERR with strMsg = error message
private GetAlarmServerInfo() {
    String errcd = ""
    String svrid = ""
    String svrip = ""
    String svrurl = ""
    String svrport = ""
    log.info "Checking Alarm Server configuration"
    if (debug) {log.info "GET: " + FeaturePaths.AlarmSvr}
    xml = SendGetRequest(FeaturePaths.AlarmSvr,"GPATH")
    if (strMsg != "OK") {
        errcd = LogGETError()
        return(errcd)
    }
    def hub = location.hubs[0]

    int i = 0
    xml.children().each {cnt -> i = i + 1}
    if (debug) {log.debug "Alarm Server Cnt: " + i.toString()}

    errcd = "NA" // didn't find it
    state.AlarmSvr = "NA"
    for (int j = 0; j < i; j++) {
        svrid = xml.HttpHostNotification[j].id.text()
        svrip = xml.HttpHostNotification[j].ipAddress.text()
        svrurl = xml.HttpHostNotification[j].url.text()
        svrport = xml.HttpHostNotification[j].portNo.text()
        if (svrip == hub.localIP) {
            if (svrurl != "/" || svrport != "39501") {
                log.info "Alarm Svr PORT: " + svrport
                log.info "Alarm Svr URL: " + svrurl
                log.info "Alarm Svr IP: " + svrip
                strMsg = "Alarm Server URL or Port is incorrect. Set to \"/\" and \"39501\""
                log.error strMsg
                state.AlarmSvr = "ERR"
                errcd = "ERR"
            } else {
                strMsg = "Alarm Server is configured for HE hub at: " + svrip + ":" + svrport + svrurl
                log.warn strMsg
                state.AlarmSvr = "OK"
                errcd = "OK"
            }
        } else {
            if (svrip != "0.0.0.0") {
                log.warn "Another Alarm Server is configured at: " + svrip + ":" + svrport + svrurl
            }
        }
    }
    if (errcd == "NA") {log.warn "Alarm Server is not available or not configured"}
    return(errcd)
}
//******************************************************************************
// GET SET STATES - GET SET STATES - GET SET STATES - GET SET STATES
//******************************************************************************
def GetSetStates() {
    Boolean err = false
    Boolean na = false
    String newfilter = ""
    String errcd = " "
    log.info "Initializing the State of All Available Features"
    if (devExclude == null) {devExclude = ""}
    errcd = "OK"
    sendEvent(name:"motion",value:"inactive")
    for (feature in FeatureCodesToName) {
        if (!devExclude.contains("$feature.key")) {
            errcd = GetSetFeatureState("$feature.value")
            if (errcd == "ERR" || errcd == "CRED") {break} 
            if (errcd == "NA") {
                na = true
                newfilter = newfilter + feature.key + "."
            }
        } else {
            sendEvent(name:"$feature.value", value:"NA")
        }
    }
    if (errcd == "ERR" || errcd == "CRED") {return(errcd)}
    if (na) {
        errcd = "NA"
        strMsg = newfilter
    }
    return(errcd)
}
//******************************************************************************
// GET SET FEATURE STATE - GET SET FEATURE STATE - GET SET FEATURE STATE
//******************************************************************************
// Returns errcd OK,NA,ERR,CRED w/strMsg=error message
private GetSetFeatureState(String Feature) {
    String errcd = ""
    String camstate = ""

    String Path = FeaturePaths."$Feature"
    
    if (debug) {log.debug "GET $Path"}

    def xml = SendGetRequest(Path, "GPATH")
    if (strMsg == "OK") {
        if (Feature == "AlarmIO") {
            camstate = xml.IOPortStatus[0].ioState.text()
            log.info "AlarmIn: " + camstate
            sendEvent(name:"AlarmIn",value:camstate)
            camstate = xml.IOPortStatus[1].ioState.text()
            log.info "AlarmOut: " + camstate
            sendEvent(name:"AlarmOut",value:camstate)
        } else {
            camstate = xml.enabled.text()
            if (camstate == "true") {camstate = "enabled"} else {camstate = "disabled"}
            sendEvent(name:"$Feature",value:camstate)
            log.info Feature + ": " + camstate
        }
        return("OK")
    } else {
        errcd = LogGETError()
        if (errcd == "NA") {
            log.info Feature + " is not available"
            sendEvent(name:"$Feature",value:"NA")
        }
        return(errcd)
    }
}
//******************************************************************************
// ALARM ON - ALARM ON - ALARM ON - ALARM ON - ALARM ON - ALARM ON - ALARM ON
//******************************************************************************
void on() {
    String cname = device.getDataValue("Name")
    cname = cname.toUpperCase()
    log.info "Received request to Trigger Alarm on " + cname
    if (device.currentValue("AlarmOut") == "NA") {
        log.warn "Alarm Out Feature is excluded or not available"
        return}
    if (!Ok2Run()) {return}
    log.warn "TRIGGER ALARM on " + cname
    SwitchAlarm("active")
}
//******************************************************************************
// ALARM OFF - ALARM OFF - ALARM OFF - ALARM OFF - ALARM OFF - ALARM OFF
//******************************************************************************
void off() {
    String cname = device.getDataValue("Name")
    cname = cname.toUpperCase()
    log.info "Received request to Clear Alarm on " + cname
    if (device.currentValue("AlarmOut") == "NA") {
        log.warn "Alarm Out Feature is excluded or not available"
        return}
    if (!Ok2Run()) {return}
    log.warn "CLEAR ALARM on " + cname
    SwitchAlarm("inactive")
}
//******************************************************************************
// ENABLE - ENABLE - ENABLE - ENABLE - ENABLE - ENABLE - ENABLE - ENABLE - ENABLE
//******************************************************************************
void Enable(String filter) {
    String cname = device.getDataValue("Name")
    cname = cname.toUpperCase()
    log.info "Run ENABLE on $cname with filter: " + filter
    if (!Ok2Run()) {return}
    SwitchAll(filter, "true")
    return
}
//******************************************************************************
// DISABLE - DISABLE - DISABLE - DISABLE - DISABLE - DISABLE - DISABLE - DISABLE
//******************************************************************************
void Disable(String filter) {
    String cname = device.getDataValue("Name")
    cname = cname.toUpperCase()
    log.info "Run DISABLE on $cname with filter: " + filter
    if (!Ok2Run()) {return}
    SwitchAll(filter, "false")
    return
}
//******************************************************************************
// OK2RUN - OK2RUN - OK2RUN - OK2RUN - OK2RUN - OK2RUN - OK2RUN - OK2RUN - OK2RUN
//******************************************************************************
def Ok2Run() {
    String devstatus = device.currentValue("zDriver")
    String cname = device.getDataValue("Name")
    cname = cname.toUpperCase()
    if (devstatus == "FAILED") {
        log.warn "Not allowed to run"
        return(false)}
    if (devstatus == "ERR") {
        log.warn "Not allowed to run. Fix problem and Save Preferences to reset."
        return(false)}
    if (devstatus == "CRED") {
        log.warn "Not allowed to run. Fix creds or config and Save Preferences to reset."
        return(false)}
    if (!PingOK(devIP)) {
        log.warn "Camera $cname is OFFLINE, no response from ping"
        sendEvent(name:"zDriver",value:"OFF")
        return(false)    
    }
    return(true)
}
//******************************************************************************
// SWITCH ALARM - SWITCH ALARM -SWITCH ALARM -SWITCH ALARM -SWITCH ALARM
//******************************************************************************
private SwitchAlarm(String newstate) {
    String errcd = ""
    String devstate = device.currentValue("AlarmOut")
    String devaistate = device.currentValue("AlarmIn")
    String camstate = " "
    String camaistate = " "
    String path = FeaturePaths.AlarmIO
    if (debug) {log.info "GET " + path}
    def xml = SendGetRequest(path, "GPATH")
    if (strMsg != "OK") {
        errcd = LogGETError()
        sendEvent(name:"zDriver",value:errcd)
        return(errcd)
    }
    camstate = xml.IOPortStatus[1].ioState.text()
    camaistate = xml.IOPortStatus[0].ioState.text()
    if (camstate == newstate) {
        log.info "OK, already " + newstate
        if (newstate != devstate) {sendEvent(name:"AlarmOut", value:newstate)}
        if (camaistate != devaistate) {sendEvent(name:"AlarmIn", value:camaistate)}
        
        sendEvent(name:"zDriver",value:"OK")
        return("OK")
    }
//  Current state is reported as active/inactive    
//  To send the trigger, we set the outputState to high/low
    if (newstate == "active") {newstate = "high"}
    if (newstate == "inactive") {newstate = "low"}

    // Can't get any easier than this...
    strXML = "<IOPortData version=\"1.0\" xmlns=\"http://www.hikvision.com/ver10/XMLSchema\">" +\
"<outputState>" + newstate + "</outputState></IOPortData>"

    path = FeaturePaths.AlarmOut

    xml = SendPutRequest(path, strXML)

    if (strMsg == "OK") {
        if (newstate == "high") {newstate = "active"} else {newstate = "inactive"}
        log.info "OK, Alarm Out is now " + newstate
        sendEvent(name:"AlarmOut", value:newstate)
        sendEvent(name:"zDriver", value:"OK")
        // Need to wait for the straggler Alarm alerts to be processed
        // before reseting AlarmIn on the device, otherwise it just gets triggered again
        // by the Alarm Server, leaving an out of sync condition. Play it safe, wait 60.
        if (newstate == "inactive" && state.AlarmSvr == "OK") {
            log.info "Waiting 1 minute for Alarm Event messages from camera to stop"
            runIn(60, RefreshAlarmStates, overwrite)
        } else {
            runIn(5, RefreshAlarmStates, overwrite)
        }
        return("OK")
    }
    log.error "PUT Error: " + strMsg
    if (strMsg.contains("code: 403") && strMsg.contains("Forbidden")) {
        log.error "Operator does not have Remote Parameters or Remote Notify options selected"
        sendEvent(name:"zDriver",value:"CRED")
        return("CRED")
    }
    sendEvent(name:"zDriver",value:"ERR")
    return("ERR")    
}
//******************************************************************************
// REFRESH ALARM STATES - REFRESH ALARM STATES - REFRESH ALARM STATES
//******************************************************************************
void RefreshAlarmStates() {
    log.info "Refreshing Alarm I/O states"
    String errcd = GetSetFeatureState("AlarmIO")
    if (errcd != "OK") {
        log.error strMsg
        sendEvent(name:"zDriver",value:errcd)
    }
}
//******************************************************************************
// SWITCH ALL - SWITCH ALL - SWITCH ALL - SWITCH ALL - SWITCH ALL - SWITCH ALL
//******************************************************************************
void SwitchAll(String filter, String newstate) {
    String devstate = " "
    String errcd = " "
    String Path = ""

    if (filter == null) {filter = ""}

//  AlarmInH will only be switched if specified in the filter, by design
    if (filter != "" && filter.contains("ai")) {
        if (device.currentValue("AlarmInH") != "NA") {
            errcd = SetFeatureState("AlarmInH",newstate)
            if (errcd != "OK") {return}
        }
    } else {
        if (filter.contains("ai")) {log.info "Requested feature *ai* is NA"}
    }

    errcd = "OK"
    for (feature in MotionFeatures) {
        if (filter == "" || filter.contains("$feature.key")) {
            if (device.currentValue("$feature.value") != "NA") {
                errcd = SetFeatureState("$feature.value",newstate)
                if (errcd != "OK") {break}
            } else {
                if (filter.contains("$feature.key")) {log.info "Requested feature *$feature.key* is NA"}
            }
        }
    }
    sendEvent(name:"zDriver",value:errcd)
    return        
}
//******************************************************************************
// SET FEATURE STATE - SET FEATURE STATE - SET FEATURE STATE - SET FEATURE STATE
//******************************************************************************
private SetFeatureState(String Feature, String newstate) {
    String errcd = ""
    String devstate = device.currentValue("$Feature")
    String Path = FeaturePaths."$Feature"
    def xml = SendGetRequest(Path, "XML")
    if (strMsg != "OK") {
        errcd = LogGETError()
        sendEvent(name:"zDriver", value:errcd)
        return(errcd)
    }
    // Find first occurence, Line Cross and Intrusion have sub-features
    // that also include the enabled element.
    def i = xml.indexOf("<enabled>")
    // this should never happen, cya
    if (i == -1) {
        strMsg = "Unexpected XML structure, <enabled> element not found"
        log.error strMsg
        sendEvent(name:"zDriver", value:"ERR")
        return("ERR")
    }
    // Extract current state from xml, first 4
    String camstate = xml.substring(i+9,i+13)
    if (camstate == "fals") {camstate = "false"}

    // ditto, cma
    if (camstate != "true" && camstate != "false") {
        log.error "inSetFeatureState: XML <enabled> element is not true/false"
        log.error "inSetFeatureState: Extracted <enabled>=" + camstate
        sendEvent(name:"zDriver", value:"ERR")
        return("ERR")
    }
    if (camstate == newstate) {
        if (newstate == "true") {newstate = "enabled"} else {newstate = "disabled"}
        log.info "OK, " + Feature + " is already " + newstate
        sendEvent(name:"$Feature",value:newstate)
        sendEvent(name:"zDriver",value:"OK")
        return("OK")
    }
    if (newstate == "true") {
        xml = xml.replaceFirst("<enabled>false<", "<enabled>true<")
    } else {
        xml = xml.replaceFirst("<enabled>true<", "<enabled>false<")
    }
    if (debug) {log.info "PUT " + Path + "/enabled=" + newstate}

    xml = SendPutRequest(Path, xml)

    if (strMsg == "OK") {
        if (newstate == "true") {newstate = "enabled"} else {newstate = "disabled"}
        log.info "OK, " + Feature + " is now " + newstate
        sendEvent(name:"$Feature",value:newstate)
        sendEvent(name:"zDriver",value:"OK")
        return("OK")
    }
    log.error "PUT Error: " + strMsg
    if (strMsg.contains("code: 403")) {
        log.error "Operator does not have Remote Parameters or Remote Notify options selected"
        sendEvent(name:"zDriver",value:"CRED")
        return("CRED")
    }
    sendEvent(name:"zDriver",value:"ERR")
    return("ERR")    
}
//******************************************************************************
// SEND GET REQUEST - SEND GET REQUEST - SEND GET REQUEST - SEND GET REQUEST
//******************************************************************************
// Return XML OR GPATH with strMsg=OK, or strMsg=Error message
private SendGetRequest(String path, String rtype) {
    String credentials = device.getDataValue("CamID")
    def headers = [:] 
    def parms = [:]
    def xml = ""
    headers.put("HOST", devIP + ":" + devPort)
    headers.put("Authorization", "Basic " + credentials)
    parms.put("uri", "http://" + devIP + ":" + devPort + path)
    parms.put("headers", headers)
    parms.put("requestContentType", "application/xml")
    if (rtype == "XML") {parms.put ("textParser", true)}
    
    try {httpGet(parms) 
        { response ->
            if (debug) {
                log.debug "GET response.status: " + response.getStatus()
                log.debug "GET response.contentType: " + response.getContentType()
            }
            if (response.status == 200) {
                strMsg = "OK"
                if (rtype == "GPATH") {
                    xml = response.data
                    if (debug) {xml.'**'.each { node ->
                                log.debug "GPATH: " + node.name() + ": " + node.text()}}
                } else {
                    xml = response.data.text
                    if (debug) {log.debug groovy.xml.XmlUtil.escapeXml(xml)}
                }
            } else {
                strMsg = response.getStatus()
            }
        }}
    catch (Exception e) {
        strMsg = e.message
    }
    return(xml)
}
//******************************************************************************
// LOG GET ERROR - LOG GET ERROR - LOG GET ERROR - LOG GET ERROR
//******************************************************************************
private LogGETError() {
    log.error "GET Error: " + strMsg
    String errcd = "ERR"
    if (strMsg.contains("code: 401")) {
        log.warn "4) Operator Account requires Remote Parameters/Settings and Remote Notify options selected"
        log.warn "3) Credentials do not match or have been changed on the camera since last Save Preferences"
        log.warn "2) Network > Advanced > Integration Protocol > CGI Enabled w/Authentication=Digest/Basic"
        log.warn "1) System > Security > Web Authentication=Digest/Basic"
        log.warn "Authentication Failed, check the following:"
        errcd = "CRED"
    }
    if (strMsg.contains("code: 403")) {
        log.warn "Resource not available or is restricted at a higher level"
        errcd = "NA"
    }
    if (strMsg.contains("code: 404")) {
        log.warn "Check Network > Advanced > Integration Protocol > CGI Enabled w/Authentication=Digest/Basic"
        log.warn "Hikvision-CGI is NOT ENABLED or IP is not a Hikvision camera"
    }
    return(errcd)
}
//******************************************************************************
// SEND PUT REQUEST - SEND PUT REQUEST - SEND PUT REQUEST - SEND PUT REQUEST
//******************************************************************************
// Return strMsg=OK or strMsg=Error message
private SendPutRequest(String strPath, String strXML) {
    def xml = ""
    def credentials = device.getDataValue("CamID")
    def headers = [:] 
    def parms = [:]

    headers.put("HOST", devIP + ":" + devPort)
    headers.put("Authorization", "Basic " + credentials)
    headers.put("Content-Type", "application/xml")

    parms.put("uri", "http://" + devIP + ":" + devPort + strPath)
    parms.put("headers", headers)
    parms.put("body", strXML)
    parms.put("requestContentType", "application/xml")

    try {httpPut(parms) { response ->
        if (debug) {
            log.debug "PUT response.status: " + response.getStatus()
            log.debug "PUT response.contentType: " + response.getContentType()
        }
        if (response.status == 200) {
            xml = response.data
            strMsg = "OK"}
         else {
            strMsg = response.getStatus()
         }
    }}
    catch (Exception e) {
        strMsg = e.message
    }
}
//******************************************************************************
// PING OK - PING OK - PING OK - PING OK - PING OK - PING OK - PING OK
//******************************************************************************
private PingOK(String ip) {
    try {
        def pingData = hubitat.helper.NetworkUtils.ping(ip,3)
        int pr = pingData.packetsReceived.toInteger()
        if (pr == 3) {
            return(true)
        } else {
            return(false)
        }
    }
    catch (Exception e) {
        strMsg = e.message
        log.warn "Ping error: " + strMsg
        return(false)
    }
}
//******************************************************************************
// PARSE - PARSE - PARSE - PARSE - PARSE - PARSE - PARSE - PARSE - PARSE - PARSE
//******************************************************************************
void parse(String description) {
    String etype = ""    // eventType
    String estate = ""   // eventState
    Boolean ok = false   // Only Supported Motion Events trigger motion on this device
    Boolean ns = false   // Not Supported and Unknown Events are logged and ignored

    String cname = device.getDataValue("Name")
    cname = cname.toUpperCase()

    if (device.currentValue("zDriver") == "OFF") {
        log.warn "$cname is BACK ONLINE"
        sendEvent(name:"zDriver",value:"OK")
    }
    // Process like normal no matter state the controller is in (FAILED,ERR,CRED,OFF,OK)
    // If we got here with AlarmSvr in NA  or ERR state,
    // the alarm server was configured on the camera AFTER the last Save
    // If null, something happened during Save Preferences
    // Thus, we need to initialize because save doesn't do that if not configured at save time
    if (state.AlarmSvr == null || state.AlarmSvr == "NA" || state.AlarmSvr == "ERR") {
        log.warn "Alarm Server NOW IN USE on " + cname
        log.info "Initializing State variables"
        state.AlarmSvr = "OK"
        state.LastAlarm = "na"
        state.AlarmCount = 0
        state.LastMotionEvent = "na"
        state.LastMotionTime = "na"
        state.MotionEventCount = 0
        state.OtherEventState = "inactive"
        state.LastOtherEvent = "na"
        state.LastOtherTime = "na"
        state.OtherEventCount = 0
        state.EventMsgCount = 0
    }
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
        }
        etype = etype.substring(1,etype.length()-1)
    }
    if (!ok && !ns) {
        etype = "Unknown"
        ns = true
    }
    // Translate to user/driver friendly names
    etype = TranslateEvents."$etype"
    
    state.EventMsgCount = state.EventMsgCount + 1

    // For Unsuported Events, log the first occurence and ignore the rest
    if (ns) {
        if (state.OtherEventState == "inactive" || etype != state.LastOtherEvent) {
            log.warn "OTHER EVENT1 on " + cname + ": " + etype
            state.OtherEventState = "active"
            state.LastOtherEvent = etype
            state.LastOtherTime = new Date().format ("EEE MMM d HH:mm:ss")
            state.OtherEventCount = state.OtherEventCount + 1
        }
        // Give whatever this is a few minutes to run its course too
        // Most of these are one-time(?) but a few are ongoing like motion
        // And if thats the case, this will continue to get pushed out
        // Cant even test some of these unsupported events
        // And, all cameras behave differntly in terms of how many msgs they send
        // and the interval between while an event is in progress
        // One of my cameras sent 5 messages at 1 per second for each failed login
        runIn(300, ResetUsupEvent, overwrite)
        return
    }
    if (etype == "AlarmIn") {
        // First time in?
        if (device.currentValue("AlarmIn") == "inactive") {
            log.warn "ALARM INPUT on " + cname
            sendEvent(name:"AlarmIn",value:"active")
            state.AlarmCount = state.AlarmCount + 1
            state.LastAlarm = new Date().format ("EEE MMM d HH:mm:ss")
            return
        }
        // Ignore the rest until it gets turned off
        // Reset is handled by the off command/SwitchAlarm method
        return
    }
    // Supported Motion Event
    if (device.currentValue("motion") == "inactive") {
        log.warn "MOTION EVENT1 on " + cname + ": " + etype
        sendEvent(name:"motion",value:"active")
        state.LastMotionEvent = etype
        state.LastMotionTime = new Date().format ("EEE MMM d HH:mm:ss")
        state.MotionEventCount = state.MotionEventCount + 1
    } else {
        // We may have more than one going on
        if (etype != state.LastMotionEvent) {
            log.info "MOTION EVENT+ on " + cname + ": " + etype
            state.LastMotionEvent = etype
            state.LastMotionTime = new Date().format ("EEE MMM d HH:mm:ss")
            state.MotionEventCount = state.MotionEventCount + 1
        }
    }
    // Wait minutes, not seconds for all motion events to clear
    // Avoid unnecessary motion state changes in HE
    // Typical PIR sensors will wait 4 minutes before signaling clear
    runIn (settings.devMotionReset.toInteger() * 60, ResetMotion, overwrite)
}	
void ResetMotion() {
    String cname = device.getDataValue("Name")
    cname = cname.toUpperCase()
    sendEvent(name:"motion",value:"inactive")
    log.info "MOTION CLEARED on " + cname
}
void ResetUsupEvent() {
    String cname = device.getDataValue("Name")
    cname = cname.toUpperCase()
    state.OtherEventState = "inactive"
    log.info "OTHER EVENT CLEARED on " + cname
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
@Field static List FeatureCodes = ["ai","ao","in","lc","m","p","or","re","rx","ub"]
@Field static Map FeatureCodesToName = [
    ai:"AlarmInH",
    ao:"AlarmIO",
    in:"Intrusion",
    lc:"LineCross",
    m:"MotionD",
    p:"PIRSensor",
    or:"ObjectR",
    re:"RgnEnter",
    rx:"RgnExit",
    ub:"UBaggage"
    ]
@Field static Map MotionFeatures = [
    in:"Intrusion",
    lc:"LineCross",
    m:"MotionD",
    p:"PIRSensor",
    or:"ObjectR",
    re:"RgnEnter",
    rx:"RgnExit",
    ub:"UBaggage"
    ]
// Includes paths to system info and features not implemented
@Field static Map FeaturePaths = [
    SysInfo:"/ISAPI/System/deviceInfo",
    Network:"/ISAPI/System/Network/Interfaces/1",
    CamUsers:"/ISAPI/Security/users",
    UserPerm:"/ISAPI/Security/UserPermission/",
    AlarmSvr:"/ISAPI/Event/notification/httpHosts",
    AlarmInH:"/ISAPI/System/IO/inputs/1",
    AlarmIO:"/IO/status",
    AlarmOut:"/IO/outputs/1/trigger",
    Intrusion:"/ISAPI/Smart/FieldDetection/1",
    LineCross:"/ISAPI/Smart/LineDetection/1",
    MotionD:"/MotionDetection/1",
    ObjectR:"/ISAPI/Smart/attendedBaggage/1",
    PIRSensor:"/ISAPI/WLAlarm/PIR",
    RgnEnter:"/ISAPI/Smart/regionEntrance/1",
    RgnExit:"/ISAPI/Smart/regionExiting/1",
    UBaggage:"/ISAPI/Smart/unattendedBaggage/1",
    Face:"/ISAPI/Smart/FaceDetect/1",
    Loitering:"/ISAPI/Smart/loitering/1",
    PeopleD:"/ISAPI/Smart/peopleDetection",
    SceneChg:"/ISAPI/Smart/SceneChangeDetection/1"
]
