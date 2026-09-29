// Google Apps Script - Great Room Lighting Logger
// Paste this into Extensions > Apps Script in your Google Sheet

function doPost(e) {
  try {
    var sheet = SpreadsheetApp.getActiveSpreadsheet().getActiveSheet();
    var data = JSON.parse(e.postData.contents);
    
    // Append row with event data
    sheet.appendRow([
      data.timestamp || new Date().toISOString(),
      data.eventType || "",
      data.value || "",
      data.details || "",
      data.lux || "",
      data.mode || "",
      data.presence || ""
    ]);
    
    return ContentService.createTextOutput(JSON.stringify({success: true}))
      .setMimeType(ContentService.MimeType.JSON);
      
  } catch(error) {
    return ContentService.createTextOutput(JSON.stringify({success: false, error: error.toString()}))
      .setMimeType(ContentService.MimeType.JSON);
  }
}

function doGet(e) {
  return ContentService.createTextOutput("Great Room Lighting Logger is running");
}

// Test function - run this to verify the script works
function testLog() {
  var sheet = SpreadsheetApp.getActiveSpreadsheet().getActiveSheet();
  sheet.appendRow([
    new Date().toISOString(),
    "test",
    "success",
    "Script is working",
    "",
    "",
    ""
  ]);
}
