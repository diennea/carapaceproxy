export {doGet, doPost, doRequest};

function doGet(url, okCallback, failCallback, options) {
    options = options || {};
    options.method = "GET";
    doRequest(url, { options }, okCallback, failCallback);
}

function doPost(url, data, okCallback, failCallback, options) {
    options = options || {};
    options.method = "POST";
    if (typeof data === 'object') {
        options.headers = {
            'Content-Type': 'application/json'
        }
        options.body = JSON.stringify(data)
    } else {
        options.body = data;
    }
    doRequest(url, { options }, okCallback, failCallback);
}

function doRequest(url, opt, okCallback, failCallback) {
    fetch(url, opt.options || {}).then(r => {
        const contentType = r.headers.get("content-type")
        const data = contentType?.includes("application/json") ? r.json() : r.text()
        data.then(r.ok ? okCallback : failCallback)
    }).catch(failCallback)
}
