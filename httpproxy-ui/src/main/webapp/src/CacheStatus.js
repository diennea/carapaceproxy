import React, { Component } from 'react';
import Button from '@material-ui/core/Button';
import Paper from '@material-ui/core/Paper';
import {doRequest} from './utils';
import { withStyles } from '@material-ui/core/styles';
import Table from '@material-ui/core/Table';
import TableBody from '@material-ui/core/TableBody';
import TableCell from '@material-ui/core/TableCell';
import TableHead from '@material-ui/core/TableHead';
import TableRow from '@material-ui/core/TableRow';
import Typography from '@material-ui/core/Typography';

import Snackbar from '@material-ui/core/Snackbar';
import IconButton from '@material-ui/core/IconButton';
import CloseIcon from '@material-ui/icons/Close';


const styles = theme => ({
  root: theme.mixins.gutters({
    paddingTop: 16,
    paddingBottom: 16,
    marginTop: theme.spacing.unit * 3,
  })
});

class CacheStatus extends Component {

    constructor(props) {
        super(props);

        this.state = {
            loading: false,
            snackOpen: false
            
        };
    }

    handleClick = (ev) => {
        var ctx = this;
        doRequest({url: "/api/cache/flush", options: {
            headers: {
                "content-type": 'application/json'
            },
            method: 'GET'
        }},
                function (data) {
                   ctx.setState({snackOpen: true});
                });
    };
    handleCloseSnack = (event, reason) => {
    if (reason === 'clickaway') {
      return;
    }

    this.setState({ snackOpen: false });
  };
    

    componentDidMount() {
        this.setState({loading: true});
        var ctx = this;
        doRequest({url: "/api/cache/info", options: {
                headers: {
                    "content-type": 'application/json'
                },
                method: 'GET'
            }}, function (data) {
            console.log('fetch2', data)
            ctx.setState({
                loading: false,
                ...data});
        });
    }

    render() {
        if (this.state.loading) {
            return (<div>
                <p>Loading...</p>
            </div>

                    );
        }
        
        const {classes} = this.props;
        return (
                <Paper className={classes.root} elevation={4}>
        <Typography variant="headline" component="h3">
        
        Cache status&nbsp;
        <Button variant="outlined" color="primary" onClick={this.handleClick}>Flush Cache</Button>
        </Typography>
        
        <Table className={classes.table}>
        <TableHead>
          <TableRow>
            <TableCell>Status</TableCell>
            <TableCell numeric>Size</TableCell>
            <TableCell numeric>Misses</TableCell>
            <TableCell numeric>Direct memory used</TableCell>
            <TableCell numeric>Heap memory used</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
        <TableRow >
            <TableCell component="th" scope="row">{this.state.result}</TableCell>
            <TableCell numeric>{this.state.cachesize}</TableCell>
            <TableCell numeric>{this.state.misses}</TableCell>
            <TableCell numeric>{this.state.directMemoryUsed}</TableCell>
            <TableCell numeric>{this.state.heapMemoryUsed}</TableCell>
        </TableRow>
        
        
        </TableBody></Table>
                    <Snackbar anchorOrigin={{vertical: 'bottom',horizontal: 'left'}}
                                open={this.state.snackOpen}
                            autoHideDuration={6000}
                            onClose={this.handleCloseSnack}
                        ContentProps={{'aria-describedby': 'message-id'}} 
                            message={<span id='message-id'>Cache flushed</span>}
                        />
                </Paper>
                );
    }
}

export default withStyles(styles)(CacheStatus);
