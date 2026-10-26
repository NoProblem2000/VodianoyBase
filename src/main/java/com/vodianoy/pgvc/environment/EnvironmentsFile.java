package com.vodianoy.pgvc.environment;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/** Content of pgvc-environments.json. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EnvironmentsFile {

    private String activeEnvironmentId;

    private List<Environment> environments = new ArrayList<>();
}
