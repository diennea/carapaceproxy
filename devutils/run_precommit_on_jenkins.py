#!/bin/python3

import requests, argparse, os, json
from urllib.parse import urlencode
from requests.packages.urllib3.exceptions import InsecureRequestWarning
from python_utils import utils
from datetime import datetime

class JenkinsConnector:
    
    requests.packages.urllib3.disable_warnings(InsecureRequestWarning)

    def __init__(self, **kwargs):
        self.jenkins_url = kwargs['jenkins_url']
        self.job_name = kwargs['job_name']
        self.params = kwargs['params']
        
    def launch_job(self, username, jenkins_token):
        url_params = urlencode(self.params)
        job_url = self.jenkins_url + "/job/" + self.job_name
        post_url = job_url + "/buildWithParameters?" + url_params
        print("Send POST HTTP Request to %s" % post_url)
        r = requests.post(post_url, auth=(username,jenkins_token), verify=False)
        if (r.status_code > 300 or r.status_code < 200):
            print(utils.red("ERROR ON REQUEST: CODE: %s\n" % r.status_code))
            print(r.text)
            return 1
        else:
            print(utils.green("Build started correctly. %s" % (job_url)))        
            return 0
        

def add_comment_on_jira(params, jira_base_url, username, jira_id):
    

    base_url = jira_base_url + "/rest/api/2/issue/"+jira_id


    pwd = utils.get_store_password_from_secret_tool('jirarestapi')
    r = requests.get(base_url, auth=(username,pwd), verify=False)
    
    if r.status_code == 401 or r.status_code == 403:
        # Maybe password changed or bad password stored
        pwd = utils.get_store_password_from_secret_tool('jirarestapi', True)

    r = requests.get(base_url, auth=(username,pwd), verify=False)
    
    comment = "Started Precommit Job\nStart time: *%s*\nBranch: *%s*" % (
            datetime.now().strftime('%Y-%m-%d %H:%M:%S'), 
            params['JK_GITCOMMITID']
        )
    data = {"update": {"comment": [{"add": {"body": comment}}]}}

    r = requests.put(base_url, 
            auth=(username,pwd), 
            verify=False, 
            data = json.dumps(data), 
            headers = {'Content-type': 'application/json'})

    if (r.status_code > 300 or r.status_code < 200):
        print("Error on updating issue %s " % r.text)
        return
    print("Comment added on issue %s" % jira_id)

def run_job_and_add_comment(jenkins_url, jenkins_job, jira_issue_id, params, args, config):
    cont = JenkinsConnector(jenkins_url=jenkins_url, job_name=jenkins_job, params=params)
    result =  cont.launch_job(args.user, args.token)
    if result == 0:
        jira_url = ""
        if "jira.url.api" in config and config["jira.url.api"]:
            jira_url = config["jira.url.api"]
            add_comment_on_jira(params, jira_url, args.user, jira_issue_id)
        else:
            print("jira.url.api not defined in %s" % args.config)
    return result

def main():
    if not utils.is_git_repository():
        print("Not a git repository")
        return 1
    
    default_username = utils.get_pc_username()
    token = utils.get_store_password_from_secret_tool("jenkinsdevtoken")
    script_path = os.path.dirname(os.path.abspath(__file__))
    parser = argparse.ArgumentParser()
    parser.add_argument('--token', metavar='TOKEN', help='Jenkins token. Default is token stored in "jenkinsdevtoken" secret-tool', default=token)
    parser.add_argument('--user', metavar='USER', help='Jenkins username', default=default_username)
    parser.add_argument('--config', metavar='CONFIG', help='Configuration file path', default=script_path + "/../mergeissue.conf")
    parser.add_argument('--branch', metavar='BRANCH', help='Git branch', default=utils.get_current_branch())

    #parser.add_argument('--db', metavar='DATABASE_TYPE', help='Database',  choices=['h2', 'pg', 'sqlserver', 'oracle'], default=None)
    #parser.add_argument('--java', metavar='JAVA_VERSION', help='Java version', choices=['oracle-8', 'oracle-10', 'oracle-11', 'openjdk-11'], default="oracle-10")
    #parser.add_argument('--suite', metavar='SUITE', help='Tests suite', choices=['QUICK', 'MAT', 'SPOTBUGS', 'MAT-SPOTBUGS', 'FULL', 'DBPATCH'], default="MAT")
    #parser.add_argument('--hreal', type=bool ,help='HBase real', default=False, const=True, nargs='?')

    args = parser.parse_args()

    
    jira_issue_ids = utils.extract_jira_issue_ids(args.branch)
    jira_issue_id = ""
    if len(jira_issue_ids) == 0:
        print("JIRA Issue not found in branch %s" % args.branch)
    else:
        jira_issue_id = jira_issue_ids[0]
        if len(jira_issue_ids) > 1:
            print("Found %s JIRA Issue in branch %s, using %s" % (len(jira_issue_ids), args.branch, jira_issue_id))
    
    config = utils.load_properties(args.config)
    if not "project" in config:
        print("config file malformed, no 'project' section found at %s" % args.config)
        return 1
    jenkins_url = ""
    config = config['project']
    if "jenkins.url" in config and config["jenkins.url"]:
        jenkins_url = config["jenkins.url"]
    else:
        print("jenkins.url not defined in %s" % args.config)
        return 1
    
    jenkins_job = ""
    if "jenkins.url" in config and config["jenkins.job"]:
        jenkins_job = config["jenkins.job"]
    else:
        print("jenkins.job not defined in %s" % args.config)
        return 1

    params = {}
    params['JK_GITCOMMITID'] = args.branch
    params['JIRA_ISSUE'] = jira_issue_id  

    return run_job_and_add_comment(jenkins_url, jenkins_job, jira_issue_id, params, args, config)


exit(main())
