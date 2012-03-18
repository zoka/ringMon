// ringmon.js
//
// Refers to html stuff at monview.html


// Drag and drop from http://github.com/gaarf/jqDnR-touch
 /*
   * jqDnR-touch - Minimalistic Drag'n'Resize for jQuery.
   * Licensed under the MIT License: http://www.opensource.org/licenses/mit-license.php
   *
   * http://github.com/gaarf/jqDnR-touch
   *
   */

(function($){

  var DOWN = 'mousedown touchstart',
      MOVE = 'mousemove touchmove',
      STOP = 'mouseup touchend',
      E, M = {};

  function xy(v) {
    var y = v.pageY,
        x = v.pageX,
        t = v.originalEvent.targetTouches;
    if(t) {
      x = t[0]['pageX'];
      y = t[0]['pageY'];
    }
    return {x:x,y:y};
  }

  function toTop($e) {
    var z = 1;
    $e.siblings().each(function(){
      z = Math.max(parseInt($(this).css("z-index"),10) || 1,z);
    });
    return $e.css('z-index', z+1);
  }

  function init(e,h,k) {
    return e.each( function() {
      var $box = $(this),
          $handle = (h) ? $(h,this).css('cursor',k) : $box;
      $handle.bind(DOWN, {e:$box,k:k}, onGripStart);
      if(k=='move') {
        $box.bind(DOWN,{},function(){toTop($box).trigger('jqDnRtop')});
      }
    });
  };

  function onGripStart(v) {
    var p = xy(v), f = function(k) { return parseInt(E.css(k))||false; };
    E = toTop(v.data.e);
    M = {
      X:f('left')||0, Y:f('top')||0,
      W:f('width')||E[0].scrollWidth||0, H:f('height')||E[0].scrollHeight||0,
      pX:p.x, pY:p.y, k:v.data.k, o:E.css('opacity')
    };
    E.css({opacity:0.7}).trigger('jqDnRstart');
    $(document).bind(MOVE,onGripDrag).bind(STOP,onGripEnd);
    return false;
  };

  function onGripDrag(v) {
    var p = xy(v);
    if(M.k == 'move') {
      if(!E.css('position').match(/absolute|fixed/)) {
        E.css({position:'relative'});
      }
      E.css({ left:M.X+p.x-M.pX, top:M.Y+p.y-M.pY } );
    }
    else { // resize
      E.css({ width:Math.max(p.x-M.pX+M.W,0), height:Math.max(p.y-M.pY+M.H,0) });
    }
    return false;
  };

  function onGripEnd() {
    $(document).unbind(MOVE,onGripDrag).unbind(STOP,onGripEnd);
    E.css({opacity:M.o}).trigger('jqDnRend');
  };

  $.fn.jqDrag = function(h) { return init(this, h, 'move'); };
  $.fn.jqResize = function(h) { return init(this, h, 'se-resize'); };

})(jQuery);

var fastPoll = 500;
var normPoll = 2000;
var flagPeriodic = false;
var lastAjaxRequestTime = 0;
var nReplPending = false;

var replSession="One";
var replPrinter;

var replIn, replOut;
var replHist = [];
var nextHistNdx;
var ndxHist ;

var annMonData = {}; // annotated JSON of monitoring data, adding __hidden: true
                     // into object data will colapse it on display


$(document).ready(function() {
  // executed upon page load

  // button handlers
  $("#getmondata").click(clickGetMonData);
  $("#dojvmgc").click(clickDoJvmGc);
  $("#irq").click(replBreak);
  $("#submit").click(clickReplSubmit);
  $("#sendmsg").click(clickSendMsg);

  // initial refresh
  clickGetMonData();

 // periodic checkbox data refresh
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


function clickReplSubmit() {
  replSubmit(replIn);
}

function trim(stringToTrim) {
  return stringToTrim.replace(/^\s+|\s+$/g,"");
}
function ltrim(stringToTrim) {
  return stringToTrim.replace(/^\s+/,"");
}
function rtrim(stringToTrim) {
  return stringToTrim.replace(/\s+$/,"");
}

var parentUri = "none";

function validateConfig(obj) {
  if (isObject(obj)) {
    var fPoll = obj["fast-poll"];
    var nPoll = obj["norm-poll"];
    if (nPoll >= 200 && nPoll <= 2000 &&
        fPoll >= 200 && fPoll <= 500  &&
        fPoll <= nPoll)

      normPoll = nPoll;
      fastPoll = fPoll;

      var freshParentUri = obj["parent-uri"];
      if (freshParentUri != parentUri) {
        parentUri = freshParentUri;
        $('#parentlink').empty();
        if (parentUri != "") {
          var html = 'Go back to the '+
          '<a href="' + parentUri+'">the application</a>'+
          ' that this page has been injected into.';
          $('#parentlink').append(html);
        }
     }
  }
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

function sendIrcMsg(e) {
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
    b = s;  // send sellection only, no need to flush the current buffer

  $("#sendmsg").attr("disabled", false);   // disable SendMsg button
  doAjaxCmd (
  {
    cmd: "chat-send",
    msg: b,
    sess: replSession
  });
}

function clickSendMsg() {
  sendIrcMsg(replIn);
}

function handleIrcMsg(m) {
  m = rtrim(m);
  if (m == "")
    return;
  m += "\n";
  appendBuffer(replOut, m);
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
+ "\n" + '  (println \"i =\"i)'
+ "\n" + "  (Thread/sleep 1000)"
+ "\n" + "  (if (< i 10)"
+ "\n" + "    (recur (inc i))"
+ "\n" + "    i))      ; Press Ctrl-Enter or 'Submit' button to execute."
+ "\n" + "             ; Once started, the execution of this script can be "
+ "\n" + "             ; stopped by 'Interrupt' button";

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

  // initial state of history - clojScript is already in
  // so Ctrl-Down will produce blank screen
  replHist[0] = clojScript;
  nextHistNdx = 1;
  ndxHist = 0;

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
    setTimeout(periodicGetMonData, fastPoll);
    return;
  }

  if (!flagPeriodic)
    return;
  var delta = getMsec() - lastAjaxRequestTime;
  if ( delta > normPoll)
    clickGetMonData();
  setTimeout(periodicGetMonData, normPoll);  // issue periodic request every normPoll msec if enabled
}

function clickDoJvmGc() {
  $("#dojvmgc").attr("disabled", true); // disable button until GC is executed
  doAjaxCmd(
    {
      cmd: "do-jvm-gc"
    });
}

function createReplPrinter (editor) {
  var theMode ="";  // current output mode
  var buf = "";     // text in 'theMode' buffered so far
  var e;            // editor instance

  e = editor;

  this.print = function (mode, text) {
    if (theMode == "")  // only one time init
      theMode = mode;

    if (mode != theMode) {
      flush();
      theMode = mode;
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
        break;
      case "chat-send":
        $("#sendmsg").attr("disabled", false);
        clickGetMonData(); // refresh monitoring data
        break;
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
    if (name == "config") {
      var val = jdata[name];
      validateConfig(val);
      continue;         // skip config
    }
    if (name == "chatMsg") {
      var val = jdata[name];
      handleIrcMsg(val);
      continue;         // skip chatMsg
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

