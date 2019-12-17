function doGet(url, okCallback, failCallback, options) {
    options = options || {};
    options.method = "GET";
    doRequest(url, { options }, okCallback, failCallback);
}

function doPost(url, data, okCallback, failCallback, options) {
    options = options || {};
    options.method = "POST";
    options.body = data;
    doRequest(url, { options }, okCallback, failCallback);
}

function doRequest(url, opt, okCallback, failCallback) {
    if (process.env.NODE_ENV !== "production") {
        okCallback(mockRequest(url));
    } else {
        fetch(url, opt.options || {})
            .then(r => {
                if (!r.ok) {
                    throw new TypeError("Request failed");
                }
                var contentType = r.headers.get("content-type");
                if (contentType && contentType.includes("application/json")) {
                    return r.json();
                } else if (contentType && contentType.includes("text/plain")) {
                    return r.text();
                } else {
                    throw new TypeError("Response Content-Type not supported");
                }
            })
            .then(data => {
                if (okCallback) {
                    okCallback(data);
                } else {
                    console.log(data);
                }
            })
            .catch(e => {
                if (failCallback) {
                    failCallback(e);
                } else {
                    console.log(e);
                }
            });
    }
}

function mockRequest(url) {
    switch (url) {
        case "/api/cache/info":
            return {
                result: "OK",
                cachesize: 50
            };
        case "/api/cache/inspect":
            return [
                {
                    hits: 0,
                    totalSize: 1178,
                    method: "GET",
                    cacheKey: "GET | localhost:4089 | /test/image.png",
                    host: "localhost:4089",
                    heapSize: 0,
                    expiresTs: 1540397475000,
                    directSize: 892,
                    uri: "/test/image.png",
                    creationTs: 1540393875788
                }
            ];
        case "/api/cache/flush":
            return {
                result: "OK",
                cachesize: 0
            };
        case "/api/backends":
            return {
                localhost8086: {
                    host: "test",
                    port: 3000,
                    openConnections: 50,
                    totalRequests: 99,
                    lastActivityTs: 1012123,
                    isAvailable: true,
                    reportedAsUnreachable: false,
                    reportedAsUnreachableTs: 1012123,
                    lastProbeTs: 1012123,
                    lastProbeSuccess: true,
                    lastProbeResult: "MOCK1<br>MOCK1<br>MOCK1"
                },
                localhost3000: {
                    host: "test",
                    port: 3000,
                    openConnections: 50,
                    totalRequests: 99,
                    lastActivityTs: 1012123,
                    isAvailable: true,
                    reportedAsUnreachable: false,
                    reportedAsUnreachableTs: 1012123,
                    lastProbeTs: 1012123,
                    lastProbeSuccess: true,
                    lastProbeResult: "MOCK1<br>MOCK1<br>MOCK1"
                },
                localhost8000: {
                    host: "test2",
                    port: 8080,
                    openConnections: 50,
                    totalRequests: 99,
                    lastActivityTs: 0,
                    isAvailable: false,
                    reportedAsUnreachable: true,
                    reportedAsUnreachableTs: 0,
                    lastProbeTs: 0,
                    lastProbeSuccess: false,
                    lastProbeResult: "MOCK2<br>MOCK2<br>MOCK2"
                }
            };
        case "/api/certificates":
            return {
                "*": {
                    id: "*",
                    hostname: "hostname*",
                    sslCertificateFile: "conf/localhost.p12"
                },
                cert1: {
                    id: "cert1",
                    hostname: "hostname1",
                    sslCertificateFile: "conf/hostname1.p12"
                },
                cert2: {
                    id: "cert2",
                    hostname: "hostnam2",
                    sslCertificateFile: "conf/hostname2.p12"
                }
            };
        case "/api/listeners":
            return {
                "0.0.0.0:4089": {
                    host: "0.0.0.0",
                    port: 4089,
                    ssl: true,
                    ocsp: true,
                    sslCiphers: "ciph1",
                    defaultCertificate: "*"
                },
                "0.0.0.0:8089": {
                    host: "0.0.0.0",
                    port: 8089,
                    ssl: false,
                    ocsp: true,
                    sslCiphers: "ciph2",
                    defaultCertificate: "*"
                }
            };
        case "/api/requestfilters":
            return [
                {
                    type: "match-user-regexp",
                    values: {
                        parameterName: "Parameter name",
                        compiledPattern: "compiledPattern"
                    }
                },
                {
                    type: "match-user-regexp",
                    values: {
                        parameterName: "Parameter name",
                        compiledPattern: "compiledPattern"
                    }
                },
                {
                    type: "match-session-regexp",
                    values: {
                        parameterName: "Parameter name",
                        compiledPattern: "compiledPattern"
                    }
                },
                { type: "add-x-forwarded-for", values: {} },
                {
                    type: "match-session-regexp",
                    values: {
                        parameterName: "Parameter name",
                        compiledPattern: "compiledPattern"
                    }
                },
                {
                    type: "match-user-regexp",
                    values: {
                        parameterName: "Parameter name",
                        compiledPattern: "compiledPattern"
                    }
                }
            ];
        case "/api/users/all":
            return ["admin", "manager", "guest"];
        default:
            if (url.includes("/api/certificates/")) {
                return {
                    "*": {
                        id: "*",
                        hostname: "",
                        sslCertificateFile: "conf/localhost.p12"
                    }
                };
            }
    }
    return {};
}

export { doRequest, doGet, doPost };
