#
# Create and start a container to run a Jenkins slave for a specific job. Standard input is sent
# to the slave jar and output from the slave jar is written to standard output. Any messages related
# to launching or running the slave container are written to standard error.
#

import sys
import time
import argparse
import json
import socket
import threading
import re
import os
import hashlib
from functools import partial
import binascii

import docker


ENV_VAR_PATTERN = re.compile(r"^[a-zA-Z_][a-zA-Z_0-9]*?=.*$")
VOLUME_PATTERN = re.compile(r"^(/.+?):(/.+?)(?::(.+))?$")

INVALID_CONTAINER_ESCAPE = '_'
INVALID_INITIAL_CONTAINER_CHAR = re.compile(r"[^a-zA-Z0-9]")
INVALID_CONTAINER_CHARS = re.compile(r"[^a-zA-Z0-9.-]")  # _ is not here because is used as escape


def message(value):
    sys.stderr.write(value)
    sys.stderr.write('\n')
    sys.stderr.flush()


def hash_file(f):
    hash = hashlib.md5()

    if os.path.isfile(f):
        with open(f, 'rb') as fh:
            for buf in iter(partial(fh.read, 128), b''):
                hash.update(buf)

    return hash.hexdigest()


def pull_job_image(docker_client, name):
    for line in docker_client.pull(name, stream=True):
        pull_msg = json.loads(line.decode('utf-8'))

        # Ignore some common messages
        if pull_msg.get('status') in ('Pulling dependent layers', 'Download complete'):
            continue

        if 'error' in pull_msg:
            raise Exception(pull_msg['error'])

        if 'progress' in pull_msg:
            message('{}: {}'.format(pull_msg['status'], pull_msg['progress']))
        else:
            message(pull_msg['status'])


def find_job_container(docker_client, name):
    try:
        return docker_client.inspect_container(name)
    except docker.errors.APIError as ex:
        if ex.response.status_code == 404:
            return None
        else:
            raise


def env_to_map(env):
    if env is None:
        return {}

    return {key: value for key, value in [entry.split('=', 2) for entry in env]}


def container_changed(docker_client, container_info, create_opts):
    # Check if name of the container parent image has changed
    if create_opts['image'] != container_info['Config']['Image']:
        message('Image name changed: expected={}, found={}'.format(
            create_opts['image'], container_info['Config']['Image']))
        return True

    # Check if command used to launch container has changed
    if create_opts['command'] != container_info['Config']['Cmd']:
        message('Command changed: expected={}, found={}'.format(
            create_opts['command'], container_info['Config']['Cmd']))
        return True

    # Check if volume list has changed
    container_vols = (container_info['Config']['Volumes'] or {}).keys()
    expected_vols = set(create_opts['volumes'])
    if container_vols != expected_vols:
        message('Volumes changed: expected={}, found={}'.format(expected_vols, container_vols))
        return True

    # Check to see if container parent image has been modified
    image_info = docker_client.inspect_image(create_opts['image'])

    if image_info['Id'] != container_info['Image']:
        return True

    # Check to see if the environment variables have changed
    # Add the environment from the image to the create environment and compare to existing container
    expected_env = env_to_map(image_info['Config']['Env'])
    expected_env.update(env_to_map(create_opts['environment']))

    container_env = env_to_map(container_info['Config']['Env'])

    if len(expected_env) != len(container_env):
        message('Environment changed: expected={}, found={}'.format(expected_env, container_env))
        return True

    for k, v in expected_env.items():
        if k not in container_env or v != container_env[k]:
            message('Environment changed: expected={}, found={}'.format(
                expected_env, container_env))
            return True

    return False


def create_server(address, port):
    while True:
        server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        server.settimeout(10.0)

        try:
            server.bind((address, port))
            server.listen(1)
            break
        except OSError as ex:
            # retry if address already in use
            if ex.errno != 98:
                raise

            server.close()
            time.sleep(0.25)

    return server


def copy_socket_to_stdout(sock):
    try:
        while True:
            data = sock.recv(4096)

            if len(data) <= 0:
                break

            sys.stdout.buffer.write(data)
            sys.stdout.buffer.flush()
    finally:
        sock.shutdown(socket.SHUT_RD)
        sys.stdout.close()


def copy_stdin_to_socket(sock):
    try:
        while True:
            data = sys.stdin.buffer.read1(4096)

            if len(data) <= 0:
                break

            sock.sendall(data)
    finally:
        sock.shutdown(socket.SHUT_WR)
        sys.stdin.close()


def run_server(server_socket):
    # Accept one connection and stop listening for connections
    slave, slave_addr = server_socket.accept()
    server_socket.close()

    slave.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)

    try:
        th = threading.Thread(target=lambda: copy_stdin_to_socket(slave))
        th.daemon = True
        th.start()

        copy_socket_to_stdout(slave)
        th.join(5.0)
    finally:
        sys.stdin.close()
        sys.stdout.close()
        slave.close()


def env_var(value):
    if not ENV_VAR_PATTERN.match(value):
        raise argparse.ArgumentTypeError("{} is not a valid environment variable".format(value))

    return value


def volume(value):
    match = VOLUME_PATTERN.match(value)

    if not match:
        raise argparse.ArgumentTypeError("{} is not a valid directory mapping".format(value))

    access = match.group(3) or 'ro'

    if access not in ('rw', 'ro'):
        raise argparse.ArgumentTypeError("{} has invalid access specification".format(value))

    return {
        'host': match.group(1),
        'container': match.group(2),
        'ro': access == 'ro'
    }


def escape_container_char(ch):
    return binascii.hexlify(ch.encode('utf-8')).zfill(4).decode('utf-8') + INVALID_CONTAINER_ESCAPE


def encode_container_name(name):
    first = name[0]
    rest = name[1:]

    if INVALID_INITIAL_CONTAINER_CHAR.match(first):
        first = escape_container_char(first)

    return first + INVALID_CONTAINER_CHARS.sub(lambda m: escape_container_char(m.group(0)), rest)


def main(args):
    parser = argparse.ArgumentParser(
        description=('Start a new Jenkins slave in a docker container. '
                     'Output from the slave slave jar is written to stdout and input to the slave is received on stdin. '
                     'Messages related to managing the slave container are written to stderr.'))
    parser.add_argument('--image',
                        help='Docker image to launch to slave in.',
                        required=True)
    parser.add_argument('--name',
                        help='Name of the job. This will become the docker container name.',
                        required=True)
    parser.add_argument('--clean',
                        help=('Always create a new container. '
                              'The default is to reuse a previous job container if it exists and '
                              'the options are the same.'),
                        action='store_true')
    parser.add_argument('--env', '-e',
                        help='Environment variable to set in the container.',
                        metavar='NAME=VALUE',
                        action='append',
                        dest='environment',
                        type=env_var,
                        default=[])
    parser.add_argument('--volume', '-v',
                        help=('Bind a directory from the host machine into the job container. '
                              'Access can be "ro" or "rw". Default is "ro".'),
                        metavar='/host:/container[:access]',
                        action='append',
                        dest='volumes',
                        type=volume,
                        default=[])
    options = parser.parse_args(args)

    install_dir = os.path.dirname(os.path.abspath(__file__))
    slave_dir = os.path.join(install_dir, 'slave')

    with open(os.path.join(slave_dir, 'properties.sh')) as fh:
        slave_config = env_to_map(fh.readlines())

    server_address = slave_config['CONNECT_ADDRESS']
    server_port = int(slave_config['CONNECT_PORT'])

    container_name = encode_container_name(options.name)

    message('Creating slave container for job "{}" (container={})'.format(options.name, container_name))

    # TODO override docker url in configuration
    # TODO use minimum possible API version?
    docker_client = docker.Client(base_url='unix://var/run/docker.sock', version='1.15')

    # Pull the image so we have the latest version locally
    pull_job_image(docker_client, options.image)

    # Check if container exists or needs to be updated
    container_info = find_job_container(docker_client, container_name)

    create_container = True
    create_opts = {
        'image': options.image,
        'name': container_name,
        # Include a hash of the init file in the command. The hash is not actually used by the
        # launch script, but only included to ensure the command changes when the init script
        # changes. This ensures that an init script change will cause the container to be recreated.
        'command': ['/bin/bash', install_dir + '/launch_slave.sh', hash_file(slave_dir + '/init_slave.sh')],
        'volumes': [install_dir] + [v['container'] for v in options.volumes],
        'environment': options.environment
    }
    start_opts = {
        'container': None,  # container id; set later
        'binds': dict(
            {v['host']: {'bind': v['container'], 'ro': v['ro']}
             for v in options.volumes},
            **{slave_dir: {
                'bind': install_dir,
                'ro': True
            }
            })
    }

    if container_info is None:
        message('No existing container found. Will create new container for job "{}"'.format(
            options.name))
    else:
        create_container = options.clean or \
                           container_changed(docker_client, container_info, create_opts)

        if create_container:
            message('Deleting old container {} for job "{}"'.format(
                container_info['Id'], options.name))
            docker_client.remove_container(container_info['Id'], v=True, force=True)
        else:
            message('Reusing existing container {} for job "{}"'.format(
                container_info['Id'], options.name))
            start_opts['container'] = container_info['Id']

    if create_container:
        message('Creating container: {}'.format(create_opts))
        create_result = docker_client.create_container(**create_opts)
        start_opts['container'] = create_result['Id']

        for warning in create_result.get('Warnings') or []:
            message('Warning: {}'.format(warning))
    else:
        # Kill the container, if it is currently running
        docker_client.kill(start_opts['container'])

    server = create_server(server_address, server_port)

    message('Starting container: {}'.format(start_opts))
    docker_client.start(**start_opts)

    try:
        run_server(server)
    finally:
        if options.clean:
            message('Deleting container {} for job "{}"'.format(
                start_opts['container'], options.name))
            docker_client.remove_container(start_opts['container'], v=True, force=True)
        else:
            message('Stopping container {} for job "{}"'.format(
                start_opts['container'], options.name))
            docker_client.kill(start_opts['container'])


if __name__ == '__main__':
    main(sys.argv[1:])
