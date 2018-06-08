function doRequest(opt, callback) {
    if (process.env.NODE_ENV !== "production") {
        var d;
        if (opt.url === "/api/cache/info") {
            d = {result: 'OK', cachesize: 50}
        } else if (opt.url === "/api/backends") { 
            d = [{host: 'test', port: 3000, openConnections: 50, totalRequests: 99, lastActivityTs: 1012123}, {host: 'test2', port: 3080, openConnections: 50, totalRequests: 99, lastActivityTs: 1012123}]
        }

        callback(d);



    } else {
        fetch(opt.url, opt.options || {}).then(r => r.json()).then(callback);
    }

}


export  {doRequest};


