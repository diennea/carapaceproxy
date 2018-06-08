import React, { Component } from 'react';
import CacheStatus from './CacheStatus';
import Backends from './Backends';
import { HashRouter as Router, Route, Link } from "react-router-dom";
import { withStyles } from '@material-ui/core/styles';
import List from '@material-ui/core/List';
import ListItem from '@material-ui/core/ListItem';
import ListItemText from '@material-ui/core/ListItemText';
import Divider from '@material-ui/core/Divider';


const styles = theme => ({
    
  sidebar: {
    width: '30%',
    height: '100%',
    maxWidth: 200,
    backgroundColor: theme.palette.background.paper,
    position: 'fixed',
     overflowXx: 'hidden',
     top: 0,
    left: 0,
    borderRight: '1px solid rgba(0, 0, 0, 0.12)'
  },
  nolink: {
      textDecoration: 'none',
      color: 'currentcolor'
  },
  content:{
      marginLeft: '200px', 
    padding: '3px 30px'
  }
});

class App extends Component {
  constructor(props) {
    super(props);
  }
  render() {
      
       const { classes } = this.props;
    return (
            
      <Router>
          <div>
          
      <div className={classes.sidebar}>
      <List component="nav">
        <ListItem button>
            <ListItemText primary="Http Proxy" />
        </ListItem>
        </List>
        <Divider />
       <List component="nav">
        <Link className={classes.nolink} to="/cache">
        <ListItem button>
          <ListItemText primary="Cache" />
        </ListItem>
        </Link>
        <Link className={classes.nolink} to="/backends">
        <ListItem button>
          <ListItemText primary="Backends" />
        </ListItem>
        </Link>
      </List>
      </div>
      
        <div className={classes.content}>
          <Route exact path="/" component={CacheStatus} />
            <Route path="/cache" component={CacheStatus} />
            <Route path="/backends" component={Backends} />
            </div>
            </div>
    </Router>
    
    );
  }
}

export default withStyles(styles)(App);
