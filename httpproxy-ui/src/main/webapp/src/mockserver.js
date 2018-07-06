function doRequest(url, opt, callback) {
    if (process.env.NODE_ENV !== "production") {
        var d;
        if (url === "/api/cache/info") {
            d = {result: 'OK', cachesize: 50}
        } else if (url === "/api/backends") { 
            d = {'localhost8086': {host: 'test', port: 3000, openConnections: 50, totalRequests: 99, lastActivityTs: 1012123},
             'localhost8000': {host: 'test2', port: 3080, openConnections: 50, totalRequests: 99, lastActivityTs: 1012123}}
        }
        callback(d);
    } else {
        fetch(url, opt.options || {}).then(r => r.json()).then(callback);
    }

}


export {doRequest};
