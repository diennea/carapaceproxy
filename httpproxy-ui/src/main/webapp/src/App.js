import React, { Component } from 'react';
import CacheStatus from './CacheStatus';
import Home from './Home';
import { HashRouter as Router, Route, Link } from "react-router-dom";


class App extends Component {
  constructor(props) {
    super(props);
  }
  render() {
    return (
      <Router>
      <div>
         <ul>
          <li>
            <Link to="/">Home</Link>
          </li>
          <li>
            <Link to="/cache">Cache Status</Link>
          </li>
        </ul> 
        <hr />
        <Route exact path="/" component={Home} />
        <Route path="/cache" component={CacheStatus} />
        
        <hr />
      </div>
    </Router>
    );
  }
}

export default App;
