import moment from "moment";

export const PATTERN = "YYYY/MM/DD HH:mm";

export function formatTimestamp(v) {
    if (!v) {
        return "";
    }
    if (!isNaN(v)) {
        return moment(v).format(PATTERN);
    }
    return "ts:" + v;
}

export function parseTimestamp(v) {
    if (!v) {
        return 0;
    }

    return moment(v, PATTERN).valueOf();
}

export function compareTimestamp(a, b) {
    if (!a && !b) {
        return 0;
    }
    if (!a) {
        return -1;
    }
    if (!b) {
        return 1;
    }
    var _a = moment(a, PATTERN);
    var _b = moment(b, PATTERN);
    return _a > _b ? 1 : _a < _b ? -1 : 0;
}
export function toInputDateValue(v) {
    if (!v) return "";
    return new Date(v).toISOString().substr(0, 10);
}

export function toBooleanSymbol(value) {
    return value ? "Yes" : "No";
}
