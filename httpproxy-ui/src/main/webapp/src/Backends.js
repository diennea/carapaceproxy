import {doRequest} from './utils';
import React, { Component } from 'react';
import { withStyles } from '@material-ui/core/styles';
import Table from '@material-ui/core/Table';
import TableBody from '@material-ui/core/TableBody';
import TableCell from '@material-ui/core/TableCell';
import TableHead from '@material-ui/core/TableHead';
import TableRow from '@material-ui/core/TableRow';
import Paper from '@material-ui/core/Paper';

const styles = theme => ({
  root: {
    width: '100%',
    marginTop: theme.spacing.unit * 3,
    overflowX: 'auto',
  },
  table: {
    minWidth: 700,
  },
});

class Backends extends Component {

    constructor(props) {
        super(props);
        this.state = {
            loading: true
        };
    }

    componentDidMount() {
        var ctx = this;
        doRequest({url: "/api/backends", options: {
                headers: {
                    "content-type": 'application/json'
                },
                method: 'GET'
            }}, function (data) {
            ctx.setState({
                loading: false,
                backends: data});
        });
    }

    render() {
        const {classes} = this.props;
        if (this.state.loading) {
            return <p>Loading...</p>;
        }
        const allbackends = this.state.backends;
        
        return (<Paper className={classes.root}><Table className={classes.table}>
        <TableHead>
          <TableRow>
            <TableCell>Backend</TableCell>
            <TableCell numeric>Open Connections</TableCell>
            <TableCell numeric>Total Requests</TableCell>
            <TableCell numeric>Last activity</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
                    {Object.keys(allbackends).map(function (key) {
                            return(  <TableRow key={key}>
                <TableCell component="th" scope="row">
                  {allbackends[key].host}:{allbackends[key].port}
                </TableCell>
                <TableCell numeric>{allbackends[key].openConnections}</TableCell>
                <TableCell numeric>{allbackends[key].totalRequests}</TableCell>
                <TableCell numeric>{new Date(allbackends[key].lastActivityTs).toString()}</TableCell>
              </TableRow>
                                    );
                    })
                    }
                
                </TableBody>
      </Table>
      </Paper>);
    }
}

export default withStyles(styles)(Backends);
