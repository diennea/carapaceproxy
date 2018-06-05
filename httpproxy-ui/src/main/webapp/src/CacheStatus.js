import React, { Component } from 'react';

class CacheStatus extends Component {

    constructor(props) {
        super(props);

        this.state = {
            loading: false
        };
    }

    componentDidMount() {
        this.setState({loading: true});
        var ctx = this;
        fetch("/api/cache/info", {
            headers: {
                "content-type": 'application/json'
            },
            method: 'GET'
        })
                .then(data => data.json())
                .then(function (data) {
                    var newState = {loading: false, size: data.cachesize, result: data.result};
                    ctx.setState(newState);
                });
    }

    render() {
        if (this.state.loading) {
            return <p>Loading...</p>;
        }
        return (
                <div>
                    <h1>Cache Status</h1>
                    <p>Status: {this.state.result}</p>
                    <p>Size: {this.state.size}</p>
                </div>
                );
    }
}

export default CacheStatus;
