#!/bin/python3s

import subprocess, getpass, traceback, re, os
try:
    import configparser
except ImportError as e: 
    print(e)
    print('configparser not installed! Please run pip3.5 install configparser --user')
    exit(1)

try:
    import jira.client
    JIRA_IMPORTED = True
except ImportError as e:
    print(e)
    JIRA_IMPORTED = False

def get_store_password_from_secret_tool(pwdkey, force_ask = False):
    has_secret_tool = run_cmd("command -v secret-tool")
    username = get_pc_username()
    if has_secret_tool and not force_ask:
        store_pwd = ''
        try:
            store_pwd = run_cmd("secret-tool lookup %s %s" % (username,pwdkey))
        except Exception:
            pass
        
        if len(store_pwd) > 0:
            return store_pwd

    if has_secret_tool:
        run_cmd("secret-tool store --label='%s' %s %s" % (pwdkey,username,pwdkey))
        pwd = run_cmd("secret-tool lookup %s %s" % (username,pwdkey))
    else:
        pwd = ""
        while len(pwd) == 0:
            pwd = getpass.getpass("Password?: ")
        
    return pwd

def stage_area_dirty():
    return len(run_cmd(['git', 'status', '--porcelain']).strip()) != 0

def green(string):
    return '\x1b[0;32;40m' + string + '\x1b[0m'    

def red(string):
    return '\x1b[0;31;40m' + string + '\x1b[0m'    

def yellow(string):
    return '\x1b[0;33;40m' + string + '\x1b[0m'    

def print_exception(e = None):
    print("\n\n" + red("=== EXCEPTION ==="))
    if not e is None:
        print(e)
    else:
        traceback.print_exc()
    print(red("=== END EXCEPTION ==="))

def get_pc_username():
    return run_shell_cmd("echo $USER").strip()

def jira_connect(jira_api_base, username = None, verify = False):
    if not JIRA_IMPORTED:
        print("Could not find jira-python library. Run 'pip3.5 install jira --user' to install.")
        return 
    if username is None:
        pc_username = get_pc_username()
        username = ask("JIRA username?: [%s]" % pc_username)
        if (len(username) == 0):
            username = pc_username
            
    pwd = getpass.getpass("JIRA password?: ")
    while len(pwd) == 0:
        pwd = getpass.getpass("JIRA password?: ")
    
    print("Try to connect to jira, url: %s" % jira_api_base)
    try:
        asf_jira = jira.client.JIRA({'server': jira_api_base, 'verify': verify},
                                basic_auth=(username, pwd), max_retries=0, validate=True)
        print("Connected to jira!")
        return asf_jira
    except jira.exceptions.JIRAError as e:
        retry = ask_yes_no("Failed to connect to JIRA: %s\nRetry?" % e.text)
        if retry:
            return jira_connect(jira_api_base)
        else:
            return None
    
def run_cmd(cmd):
    if isinstance(cmd, list):
        return subprocess.check_output(cmd).decode()
    else:
        return subprocess.check_output(cmd.split(" ")).decode()

def call_cmd(cmd, silent = False):
    if not isinstance(cmd, list):
        cmd = cmd.split(" ")

    
    

    if silent:
        with open(os.devnull, "w") as f:
            return subprocess.call(cmd, stdout=f) == 0    
    else:
        print_string = yellow("RUN COMMAND -- ")  + ' '.join(cmd) + yellow(" --")
        print(print_string)
        return subprocess.call(cmd) == 0    
        

def run_shell_cmd(cmd):
    return subprocess.check_output(cmd, shell=True).decode()


def ask(message):
    message = message if message[-1:] == " " else message + " "
    return input(message)

def ask_yes_no(message, default = True): 
    defstring = "(y/n): " +  ("[y] " if default else "[n] ")
    message = message + defstring if message[-1:] == " " else message + " " + defstring
    answer = ask(message).lower()
    if answer in ['y', 'n']: 
        return answer == 'y'
    elif len(answer) == 0:
        return default
    else:
        return ask_yes_no(message,default)

def ask_not_empty(message):
    res = ask(message)
    if res is None or res == "":
        print("Cannot be empty")
        return ask_not_empty(message)
    return res

def get_current_branch():
    return run_cmd("git rev-parse --abbrev-ref HEAD").replace("\n", "")

def extract_jira_issue_ids(string): 
    return re.findall("[A-Z]{1,}-[0-9]{1,}", string)    

def load_properties(filename):
    config = configparser.ConfigParser()
    config.read(filename)
    return config

def is_git_repository():
    return call_cmd("git rev-parse --git-dir", True) == 1
