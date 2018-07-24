function doRequest(url, opt, callback) {
    if (process.env.NODE_ENV !== "production") {
        callback(mockRequest(url));
    } else {
        fetch(url, opt.options || {}).then(r => r.json()).then(callback);
    }

}

function mockRequest(url) {
    switch (url) {
        case "/api/cache/info": return {
            result: 'OK', cachesize: 50
        };
        case "/api/cache/flush": return {
            result: 'OK', cachesize: 0
        };
        case "/api/backends": return {
            'localhost8086': { host: 'test', port: 3000, openConnections: 50, totalRequests: 99, lastActivityTs: 1012123, 
            isAvailable: true, reportedAsUnreachable: false, reportedAsUnreachableTs: 1012123, lastProbeTs: 1012123, lastProbeSuccess: "DOWN", lastProbeResult: "MOCK1<br>MOCK1<br>MOCK1"},
            'localhost8000': { host: 'test2', port: 3080, openConnections: 50, totalRequests: 99, lastActivityTs: 1012123,
            isAvailable: true, reportedAsUnreachable: false, reportedAsUnreachableTs: 1012123, lastProbeTs: 1012123, lastProbeSuccess: "DOWN", lastProbeResult: "MOCK2<br>MOCK2<br>MOCK2"}
        };
        case "/api/certificates": return {
            "*": { id: "*", hostname: "hostname*", sslCertificateFile: "conf/localhost.p12", sslCertificatePassword: "test0"},
            "cert1": { id: "cert1", hostname: "hostname1", sslCertificateFile: "conf/hostname1.p12", sslCertificatePassword: "test1" },
            "cert2": { id: "cert2", hostname: "hostnam2", sslCertificateFile: "conf/hostname2.p12", sslCertificatePassword: "test2" }
        }
        case "/api/listeners": return {
            "0.0.0.0:4089": { host: "0.0.0.0", port: 4089, ssl: true, ocps: true, sslCiphers: "ciph1", defaultCertificate: "*" },
            "0.0.0.0:8089": { host: "0.0.0.0", port: 8089, ssl: false, ocps: true, sslCiphers: "ciph2", defaultCertificate: "*" }
        }
        default:
            if (url.includes("/api/certificates/")) {
                return {"*": {"id": "*", "hostname": "", "sslCertificateFile": "conf/localhost.p12", "sslCertificatePassword": "testproxy"}};
            }
    }
    return {};
}

export { doRequest };
