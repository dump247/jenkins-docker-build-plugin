package com.github.dump247.jenkins.plugins.dockerjob.slaves;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.util.List;
import java.util.Map;

/**
 * Options to create a Jenkins slave.
 */
public class SlaveOptions {
    private final String _name;
    private final String _image;

    private boolean _cleanEnvironment;
    private Map<String, String> _environment = ImmutableMap.of();
    private List<DirectoryMapping> _directoryMappings = ImmutableList.of();

    public SlaveOptions(String name, String image) {
        _name = name;
        _image = image;
    }

    public String getImage() {
        return _image;
    }

    public String getName() {
        return _name;
    }

    public boolean isCleanEnvironment() {
        return _cleanEnvironment;
    }

    public void setCleanEnvironment(boolean cleanEnvironment) {
        _cleanEnvironment = cleanEnvironment;
    }

    public Map<String, String> getEnvironment() {
        return _environment;
    }

    public void setEnvironment(Map<String, String> environment) {
        _environment = ImmutableMap.copyOf(environment);
    }

    public List<DirectoryMapping> getDirectoryMappings() {
        return _directoryMappings;
    }

    public void setDirectoryMappings(List<DirectoryMapping> directoryMappings) {
        _directoryMappings = ImmutableList.copyOf(directoryMappings);
    }
}
