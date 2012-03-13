// ringmon.js
//
// Refers to html stuff on monview.html



var flagPeriodic = false;
var lastAjaxRequestTime = 0;
var nReplPending = false;

var replSession="One";
var cmdQ = createCommandQ();
var replPrinter;

var replIn, replOut;
var replHist = [];
var nextHistNdx = 0;
var ndxHist = 0;

var annMonData = {}; // annotated JSON of monitoring data, adding __hidden: true
                     // into object data will colapse it on display


$(document).ready(function() {
  // executed upon page load

  // button handlers
  $("#getmondata").click(clickGetMonData);
  $("#dojvmgc").click(clickDoJvmGc);
  $("#irq").click(replBreak);

  // initial refresh
  clickGetMonData();

 // periodic checkbox data refredh
  $('#periodic').on('change', function () {
    if ($(this).is(':checked')) {
      flagPeriodic = true;
      periodicGetMonData();
      $("#getmondata").attr("disabled", true);
    } else {
      flagPeriodic = false;
      $("#getmondata").attr("disabled", false);
    }
  });

  initEditor();
  $("#periodic").prop("checked", false); // make sure that periodic is initially uncheked
                                         // if this is not done loading back from browser
                                         // history toggles it on/off
  $('#periodic').trigger('click');  // periodic data update on
  $("#irq").attr("disabled", true); // interrupt button disabled
});


function trim(stringToTrim) {
  return stringToTrim.replace(/^\s+|\s+$/g,"");
}
function ltrim(stringToTrim) {
  return stringToTrim.replace(/^\s+/,"");
}
function rtrim(stringToTrim) {
  return stringToTrim.replace(/\s+$/,"");
}

function replRefresh(){
  // same command, but empty code buffer
  // means  we just want refresh for long running repl script output
  // should not be needed when we get WebSockets
  doAjaxCmd (
    {
      cmd: "do-repl",
      code: "",
      sess: replSession
    });
}

function replBreak() {
  doAjaxCmd (
  {
    cmd: "repl-break",
    sess: replSession
  });
}

function createCommandQ() {
  var q = []; // head at array start
  var pend = 0;   // index of the command in the Q that is pending
  var that = this;  //

  function remove (q, cid) {
    var r = [];
    var j = 0;
    for (var i in q)
      if (q[i] != cid)
        r[j++] = q[i];
    return r;
  }

  this.put = function (cid) {
    // only put command in the Q if not already there
    //console.log("put:" + cid);
    var found = false;
    for (var i in q) {
      if (cid == q[i]) {
        found = true;
        break;
      }
    }
    if (!found)
      q[q.length] = cid;
    //console.log("after put:" + q);
    return !found; // return true if it was queued
  },

  this.remove = function (cid) {
    //console.log("remove:" + cid);
    q = remove(q,cid);
    //console.log("after remove:" + q);
    return( q.length == 0); // return true if Q was made empty
  },

  this.getPendingCid = function () {
    if (q.length)
      return q[pend];
    else
      return "";
  }
  return this;
}

function replSubmit(e) {
  if (e != replIn)
    return;
  var b = e.getValue();
  var s = e.getSelection();
  b = rtrim(b);
  s = rtrim(s);
  if (b == "")
    return;     // nothing to do

  if (s == "") { // no selection
    e.setValue("");
    replHist [nextHistNdx++] = b;
    ndxHist = nextHistNdx; // history pointer past freshest item
    if (ndxHist > 1) {
      if (replHist[ndxHist-1] == replHist[ndxHist-2]) {
        // do not polute history with same buffer values
        ndxHist--;
        nextHistNdx--;
      }
    }
  } else
    b = s;  // submit sellection only, no need to flush the current buffer

 $("#irq").attr("disabled", false);   // enable interrupt button
  doAjaxCmd (
  {
    cmd: "do-repl",
    code: b,
    sess: replSession
  });

}

function bufferEnd(e) {
  e.setSelection(
  {
    line:e.lineCount()-1
  }, null,!0);
}

function appendBuffer(e, s) {
  bufferEnd(e);
  e.replaceSelection(s);
  bufferEnd(e);
}

function restoreBuffer(e, b) {
  b = rtrim(b);
  e.setValue(b);
  bufferEnd(e); // restore cursor at the end of last line
}

function histBack(e) {
  if (e != replIn)
    return;
  if (ndxHist > 0) {
    if (ndxHist == nextHistNdx) {
      var b = e.getValue();
      b = rtrim(b);
      if (b != "") {
        // preserve current buffer if not empty
        replHist[nextHistNdx++] = b;
      }
    }
    ndxHist--;
    if (ndxHist < nextHistNdx) {
      var b = replHist[ndxHist];
      restoreBuffer(e,b);
    }
  }
}

function histFwd(e) {
  if (e != replIn)
    return;
  if (ndxHist < nextHistNdx) {
    ndxHist++;
    if (ndxHist < nextHistNdx) {
      var b = replHist[ndxHist];
      restoreBuffer(e,b);
    } else
      e.setValue("");
  }
}

function clearEditor(e) {
  if (e != replIn)
    return;
  ndxHist = nextHistNdx;
  replOut.setValue("");
  replIn.setValue("");
}

function clearHistory(e) {
  if (e != replIn)
    return;
  ndxHist = nextHistNdx = 0;
  replIn.setValue("");
}

var clojScript =

     "(loop [i 0]"
+ "\n" + '  (println \"i=\"i)'
+ "\n" + "  (Thread/sleep 1000)"
+ "\n" + "  (if (< i 10)"
+ "\n" + "    (recur (inc i))"
+ "\n" + "    i))      ; press Ctrl-Enter to submit for execution"
+ "\n" + "             ; Once started, execution of this script can be "
+ "\n" + '             ; stopped by "Interrupt" button';

function initEditor() {
  replOut = CodeMirror.fromTextArea(document.getElementById('ClojOut'),
    {
      matchBrackets: true,
      mode: "text/x-clojure",
      readOnly:true
    });

  replIn = CodeMirror.fromTextArea(document.getElementById('Cloj'),
    {
      lineNumbers: true,
      matchBrackets: true,
      mode: "text/x-clojure"
    });

  replOut.setValue("");
  replIn.setValue(clojScript);

  CodeMirror.keyMap.default["Ctrl-Enter"] = replSubmit;
  CodeMirror.keyMap.default["Ctrl-Up"]    = histBack;
  CodeMirror.keyMap.default["Ctrl-Down"]  = histFwd;
  CodeMirror.keyMap.default["Ctrl-Home"]  = clearEditor;
  CodeMirror.keyMap.default["Ctrl-End"]   = clearHistory;
  replPrinter = createReplPrinter(replOut);
}

function clickGetMonData() {
  doAjaxCmd(
    {
      cmd: "get-mon-data",
      sess: replSession
    });
}

function getMsec() {
  return new Date().getTime();
}

function periodicGetMonData() {
  if (nReplPending) {
    nReplPending = false;
    clickGetMonData();
    setTimeout(periodicGetMonData, 500);
    return;
  }

  if (!flagPeriodic)
    return;
  var delta = getMsec() - lastAjaxRequestTime;
  if ( delta > 2000)
    clickGetMonData();
  setTimeout(periodicGetMonData, 2000);  // issue periodic request every 2 seconds if enabled
}

function clickDoJvmGc() {
  $("#dojvmgc").attr("disabled", true); // disable button until GC is executed
  doAjaxCmd(
    {
      cmd: "do-jvm-gc"
    });
}

function createReplPrinter (editor) {
  var buf = "";
  var theMode ="";
  var e; //editor instance

  e = editor;

  this.print = function (mode, text) {
    if (theMode == "")  // only one time init
      theMode = mode;

    if (mode != theMode) {
      flush();
      theMode = mode;
      cName = "";
    }

    buf += text;
  }

  this.flush = function () {
    if (buf == "" || theMode == "")
      return;

    var cName = "";
    switch (theMode) {
      case "out":
        buf = rtrim(buf) ;
        break;
      case "err":
        cName = "cm-error";
        buf = trim(buf) ;
        break;
      case "ns":
        buf = trim(buf) ;
        break;
      case "value":
        buf = rtrim(buf) ;
        cName = "cm-repl-val";
        break;
      case "code":
        buf = rtrim(buf);
        break;
    }

    bufferEnd(e);
    var from = e.getCursor();
    appendBuffer(e, buf);
    var to = e.getCursor();

    if (cName != "") {
      e.markText(from, to, cName);
    }
    // ommit trailing newline only for ns
    if (theMode != "ns")
      appendBuffer(e,"\n");
    buf = "";
  }
  return this;
}

function searchVec (v, text) {
  for (var i in v) {
    if (v[i] == text)
      return true;
  }
  return false;
}

function respDoRepl(code, jdata) {
  var s = code;
  if (code != "")
    replPrinter.print("code", code);

  for (obj in jdata) {
    var val = jdata[obj];
    if (!isObject (val))
      continue;
    if ("out" in val) {
      var text = val["out"];
      s += text;
      replPrinter.print("out", text);
    }
    if ("ns" in val) {  // ask for ns befotr value
      var text = val["ns"] +"=>";
      s += text;
      replPrinter.print("ns", text);
    }
    if ("value" in val) {
      var text = val["value"];
      s += text;
      replPrinter.print("value", text);
    }
    if ("err" in val) {
      var text = val["err"];
      s += text;
      replPrinter.print("err", text);
    }
    if ("status" in val) {
      var svec = val["status"];
      if (searchVec(svec,"interrupted")) {
        replPrinter.print("err", "Interrupted");
      }
      if (searchVec(svec,"interrupt-id-mismatch")) {
        replPrinter.print("err", "Interrupt operation failed");
      }
    }
    if ("pend" in val) {
      if (val["pend"]) {
        $("#irq").attr("disabled", false);   // enable interrupt button
      } else {
        $("#irq").attr("disabled", true);   // disable interrupt button
      }
    }
  }
  if (s != "") {
    // there was some repl output activity
    // schedule next request faster
    nReplPending = true;
  }
  replPrinter.flush();
}

function doAjaxCmd(request) {
  // use inner function to be able to refer to original request in closure
  var respCallback = function ( jdata) {
    var cmd = request["cmd"];
    switch (cmd) {
      case "get-mon-data":
        jsonToTable(jdata);
        break;
      case "do-jvm-gc":
        $("#dojvmgc").attr("disabled", false); // re-enable button on cmd ack
        clickGetMonData(); // refresh monitoring data
        break;
      case "do-repl":
        respDoRepl(request["code"], jdata);
        break;
      case "repl-break":
        respDoRepl("", jdata);
    }
  }

  lastAjaxRequestTime = getMsec();
  $.ajax("/ringmon/command",
    {
      data: request,    // JSON encoded request
      type: "GET",
      dataType: "json",
      contentType: "application/json",
      success: respCallback
    });
}

function isObject ( obj ) {
  return obj && (typeof obj  === "object");
}

// search json object jobj for the first instance of
// object named objname and
// then set its property named "__hidden" to val
function annSetHidden(jobj, objname, val) {
  for (var name in jobj) {
    var v = jobj[name];
    if (isObject(v)) {
      if (name == objname) {
        v["__hidden"] = val;
        return;
      } else
        annSetHidden(v, objname,val)
    }
  }
}

function attachHideHandler (id) {
  $("[id="+id+"]").on("change", function(event) {
    var nameCell, name;

    nameCell = event.target.parentElement.nextSibling.nextSibling;
    name = nameCell.textContent;
    name = name.slice(0, name.length - 1); // remove ":" at the end
    if ($(this).is(':checked'))
      annSetHidden(annMonData, name, false);
    else
      annSetHidden(annMonData, name,  true);
    jsonToTable(annMonData);
  });
}

function makeRow(ident, name, val, hidden) {
  var TreeStyles =
  {
    Hdr : {
      light:  ' style="background:#F5F51D"',
      dark:   ' style="background:#80800E"'},
    Name : {
      light:  ' style="background:#BAF7C3"',
      dark:   ' style="background:#5DF7C3"'},
    Val : {
      light:  ' style="background:#B5F2F5"',
      dark:   ' style="background:#B5F2F5"'}
  };

  var s = "<tr>",
  align ,
  style ,
  vstyle,
  hChk = "",     // hide check box markup
  chkState = "",
  hdr = false,   // true for header row
  hdrPad;       // markup for padding cell to align checkboxes properly

  if (val === "")
    hdr = true;

  if (hdr) {
    align = ' align="left"';
    style = TreeStyles.Hdr.light;
    vstyle = style

    if (!hidden)
      chkState  = 'checked="yes"';
    hChk = '<input type="checkbox" id="hide"'+chkState+"></input>";
  } else {
    align = ' align="right"';
    style =  TreeStyles.Name.light;
    vstyle =  TreeStyles.Val.light;
  }

  if (hdr) {
    hdrPad = "<td"+style+"></td>";
  } else {
    hdrPad  = "";
  }

  for (var i = 0; i < ident; i++) {
      s += "<td></td>";
  }
  s += "<td>"+hChk+hdrPad+"</td><td"+align+style+">"+name+
       ":</td><td align=right"+vstyle+">"+val+"</td></tr/>";
  return s;
}

function makeTbl(s, jdata, ident) {
  for (var name in jdata) {
    if (name == "nREPL") {
      respDoRepl("", jdata[name]);
      continue;         // skip nREPL
    }
    var val = jdata[name];
    if (!isObject(val)) {
      if (name != "__hidden")  // do not show hidden field, it is only for internal use
        s += makeRow(ident, name, val, false);
    }  else {
      var hidden = false;
      if ("__hidden" in val)
        hidden = val["__hidden"];

      s += makeRow(ident, name, "", hidden);
      if (!hidden)
        s += makeTbl("", val, ident+1);
    }
  }
  return s;
}

function isEmpty(obj) {
  for(var i in obj)
    return false;
  return true;
}


function annotateJson(fresh, ann) {
  var init = false;
  var firstobj = false;
  if (isEmpty (ann)) {
    // initial update set all hidden except first one
    init = true;
    firstobj = true;
  }

  for (var name in fresh) {
    if (name == "nREPL") {
      ann[name]= fresh[name];
      continue;
    }
    var val = fresh[name];
    if (isObject (val)) {
      if (!(name in ann) ) {
        ann[name]= fresh[name];
        if (init) {
          if (firstobj) {
            ann[name]["__hidden"] = false;
            firstobj = false;
          } else
            ann[name]["__hidden"] = true;
        }
      } else
        annotateJson(val, ann[name]);
      } else {
      ann[name] = fresh[name]; // fresh data value
    }
  }
}

function jsonToTable(jdata) {
  annotateJson(jdata, annMonData); // update annotated data
                                   // preserving hidden field markers, if any
  $(".tdata").empty();
  var s= makeTbl("", annMonData, 0);
  $(".tdata").append(s);
  attachHideHandler("hide");
}

