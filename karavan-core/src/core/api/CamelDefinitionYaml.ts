/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import * as yaml from 'js-yaml';
import { Integration, CamelElement, Beans } from '../model/IntegrationDefinition';
import { RouteDefinition, RegistryBeanDefinition, RouteConfigurationDefinition } from '../model/CamelDefinition';
import { CamelUtil } from './CamelUtil';
import { CamelDefinitionYamlStep } from './CamelDefinitionYamlStep';

export class CamelDefinitionYaml {
    private constructor() {}

    static integrationToYaml = (integration: Integration): string => {
        const clone: any = CamelUtil.cloneIntegration(integration);
        const flows = integration.spec.flows;
        clone.spec.flows = flows
            ?.map((f: any) => CamelDefinitionYaml.cleanupElement(f))
            .filter(x => Object.keys(x).length !== 0);
        if (integration.type === 'crd') {
            delete clone.type;
            const i = JSON.parse(JSON.stringify(clone, (key, value) => CamelDefinitionYaml.replacer(key, value), 3)); // fix undefined in string attributes
            const text = CamelDefinitionYaml.yamlDump(i);
            return text;
        } else {
            const f = JSON.parse(
                JSON.stringify(clone.spec.flows, (key, value) => CamelDefinitionYaml.replacer(key, value), 3),
            );
            const text = CamelDefinitionYaml.yamlDump(f);
            return text;
        }
    };

    static isEmpty = (value?: string): boolean => {
        return !value || (value.trim && value.trim().length === 0);
    };

    static cleanupElement = (element: CamelElement, inArray?: boolean, inSteps?: boolean): CamelElement => {
        const result: any = {};
        const object: any = { ...element };

        if (inArray) {
            object.inArray = inArray;
            object.inSteps = !!inSteps;
        }

        if (object.dslName.endsWith('Expression')) {
            delete object.language;
            delete object.expressionName;
        } else if (object.dslName.endsWith('DataFormat')) {
            delete object.dataFormatName;
        } else if (object.dslName === 'RegistryBeanDefinition') {
            if (object.properties && Object.keys(object.properties).length === 0) {
                delete object.properties;
            }
        }

        delete object.uuid;
        delete object.show;

        for (const [key, value] of Object.entries(object) as [string, any][]) {
            if (value instanceof CamelElement || (typeof value === 'object' && value?.dslName)) {
                result[key] = CamelDefinitionYaml.cleanupElement(value);
            } else if (Array.isArray(value)) {
                if (value.length > 0) {
                    result[key] = CamelDefinitionYaml.cleanupElements(value, key === 'steps');
                }
            } else if (key === 'parameters' && typeof value === 'object') {
                const parameters = Object.entries(value || {})
                    .filter(([_, v]: [string, any]) => !CamelDefinitionYaml.isEmpty(v))
                    .reduce((x: any, [k, v]) => ({ ...x, [k]: v }), {});
                if (Object.keys(parameters).length > 0) {
                    result[key] = parameters;
                }
            } else {
                if (!CamelDefinitionYaml.isEmpty(value)) {
                    result[key] = value;
                }
            }
        }
        return result as CamelElement;
    };

    static cleanupElements = (elements: CamelElement[], inSteps?: boolean): CamelElement[] => {
        const result: any[] = [];
        for (const element of elements) {
            if (typeof element === 'object') {
                result.push(CamelDefinitionYaml.cleanupElement(element, true, inSteps));
            } else {
                result.push(element);
            }
        }
        return result;
    };

    static yamlDump = (integration: Integration): string => {
        return yaml.dump(integration, {
            noRefs: false,
            noArrayIndent: false,
            sortKeys: function (a: any, b: any) {
                if (a === 'steps') return 1;
                else if (b === 'steps') return -1;
                else return 0;
            },
        });
    };

    static replacer = (key: string, value: any): any => {
        if (
            typeof value === 'object' &&
            (value.hasOwnProperty('stepName') || value.hasOwnProperty('inArray') || value.hasOwnProperty('inSteps'))
        ) {
            const stepNameField = value.hasOwnProperty('stepName') ? 'stepName' : 'step-name';
            const stepName = value[stepNameField];
            const dslName = value.dslName;
            let newValue: any = JSON.parse(JSON.stringify(value));
            delete newValue.dslName;
            delete newValue[stepNameField];

            if (
                value.inArray &&
                !value.inSteps &&
                ['intercept', 'interceptFrom', 'interceptSendToEndpoint', 'onCompletion', 'onException'].includes(
                    stepName,
                )
            ) {
                delete newValue.inArray;
                delete newValue.inSteps;
                const xValue: any = {};
                xValue[stepName] = newValue;
                return xValue;
            } else if (
                (value.inArray && !value.inSteps) ||
                dslName === 'ExpressionSubElementDefinition' ||
                dslName === 'ExpressionDefinition' ||
                dslName?.endsWith('Expression') ||
                stepName === 'otherwise' ||
                stepName === 'doFinally' ||
                stepName === 'resilience4jConfiguration' ||
                stepName === 'faultToleranceConfiguration' ||
                stepName === 'errorHandler' ||
                stepName === 'deadLetterChannel' ||
                stepName === 'defaultErrorHandler' ||
                stepName === 'jtaTransactionErrorHandler' ||
                stepName === 'noErrorHandler' ||
                stepName === 'springTransactionErrorHandler' ||
                stepName === 'redeliveryPolicy' ||
                key === 'from'
            ) {
                delete newValue.inArray;
                delete newValue.inSteps;
                return newValue;
            } else {
                delete newValue.inArray;
                delete newValue.inSteps;
                const xValue: any = {};
                xValue[stepName] = newValue;
                return xValue;
            }
        } else {
            delete value?.dslName;
            return value;
        }
    };

    static yamlToIntegration = (filename: string, text: string): Integration => {
        const integration: Integration = Integration.createNew(filename);
        const fromYaml: any = yaml.load(text);
        const camelized: any = CamelUtil.camelizeObject(fromYaml);
        if (
            camelized?.apiVersion &&
            camelized.apiVersion.startsWith('camel.apache.org') &&
            camelized.kind &&
            camelized.kind === 'Integration'
        ) {
            integration.type = 'crd';
            if (camelized?.metadata?.name) {
                integration.metadata.name = camelized?.metadata?.name;
            }
            const int: Integration = new Integration({ ...camelized });
            integration.spec.flows?.push(...CamelDefinitionYaml.flowsToCamelElements(int.spec.flows || []));
        } else if (Array.isArray(camelized)) {
            integration.type = 'plain';
            const flows: any[] = camelized;
            integration.spec.flows?.push(...CamelDefinitionYaml.flowsToCamelElements(flows));
        }
        return integration;
    };

    static yamlIsIntegration = (text: string): boolean => {
        const fromYaml: any = yaml.load(text);
        const camelized: any = CamelUtil.camelizeObject(fromYaml);
        if (
            camelized?.apiVersion &&
            camelized.apiVersion.startsWith('camel.apache.org') &&
            camelized.kind &&
            camelized.kind === 'Integration'
        ) {
            return true;
        } else if (Array.isArray(camelized)) {
            return true;
        } else {
            return false;
        }
    };
    static flowsToCamelElements = (flows: any[]): any[] => {
        const rules: { [key: string]: (flow: any) => any } = {
            restConfiguration: (flow: any) =>
                CamelDefinitionYamlStep.readRestConfigurationDefinition(flow.restConfiguration),
            rest: (flow: any) => CamelDefinitionYamlStep.readRestDefinition(flow.rest),
            route: (flow: any) => CamelDefinitionYamlStep.readRouteDefinition(flow.route),
            from: (flow: any) => CamelDefinitionYamlStep.readRouteDefinition(new RouteDefinition({ from: flow.from })),
            beans: (flow: any) => CamelDefinitionYaml.readBeanDefinition(flow),
            routeConfiguration: (flow: any) =>
                CamelDefinitionYamlStep.readRouteConfigurationDefinition(flow.routeConfiguration),
            errorHandler: (flow: any) =>
                CamelDefinitionYamlStep.readRouteConfigurationDefinition(
                    new RouteConfigurationDefinition({ errorHandler: flow.errorHandler }),
                ),
            onException: (flow: any) =>
                CamelDefinitionYamlStep.readRouteConfigurationDefinition(
                    new RouteConfigurationDefinition({ onException: flow.onException }),
                ),
            intercept: (flow: any) =>
                CamelDefinitionYamlStep.readRouteConfigurationDefinition(
                    new RouteConfigurationDefinition({ intercept: flow.intercept }),
                ),
            interceptFrom: (flow: any) =>
                CamelDefinitionYamlStep.readRouteConfigurationDefinition(
                    new RouteConfigurationDefinition({ interceptFrom: flow.interceptFrom }),
                ),
            interceptSendToEndpoint: (flow: any) =>
                CamelDefinitionYamlStep.readRouteConfigurationDefinition(
                    new RouteConfigurationDefinition({ interceptSendToEndpoint: flow.interceptSendToEndpoint }),
                ),
            onCompletion: (flow: any) =>
                CamelDefinitionYamlStep.readRouteConfigurationDefinition(
                    new RouteConfigurationDefinition({ onCompletion: flow.onCompletion }),
                ),
        };

        const result: any[] = [];

        for (const [rule, func] of Object.entries(rules)) {
            flows.filter((e: any) => e.hasOwnProperty(rule)).forEach((f: any) => result.push(func(f)));
        }

        return result;
    };

    static readBeanDefinition = (beans: any): Beans => {
        const result: Beans = new Beans();
        for (const bean of beans.beans) {
            const props: any = {};
            if (bean && bean.properties) {
                // convert map style to properties if requires
                for (const [key, value] of Object.entries(bean.properties)) {
                    CamelDefinitionYaml.flatMapProperty(key, value, new Map<string, any>()).forEach(
                        (v, k) => (props[k] = v),
                    );
                }
            }
            bean.properties = props;
            result.beans.push(new RegistryBeanDefinition(bean));
        }
        return result;
    };

    // convert map style to properties if requires
    static flatMapProperty = (key: string, value: any, properties: Map<string, any>): Map<string, any> => {
        if (value === undefined) {
            return properties;
        }

        if (typeof value === 'object') {
            for (const k in value) {
                const key2 = key + '.' + k;
                const value2: any = value[k];
                CamelDefinitionYaml.flatMapProperty(key2, value2, new Map<string, any>()).forEach((value1, key1) =>
                    properties.set(key1, value1),
                );
            }
        } else {
            properties.set(key, value);
        }
        return properties;
    };

    // add generated Integration YAML into existing Integration YAML
    static addYamlToIntegrationYaml = (
        filename: string,
        camelYaml: string | undefined,
        restYaml: string,
        addREST: boolean,
        addRoutes: boolean,
    ): string => {
        const existing =
            camelYaml !== undefined
                ? CamelDefinitionYaml.yamlToIntegration(filename, camelYaml)
                : Integration.createNew(filename);
        const generated = CamelDefinitionYaml.yamlToIntegration(filename, restYaml);

        const flows: CamelElement[] =
            existing.spec.flows?.filter(f => !['RouteDefinition', 'RestDefinition'].includes(f.dslName)) || [];

        const restE: CamelElement[] = existing.spec.flows?.filter(f => f.dslName === 'RestDefinition') || [];
        const restG: CamelElement[] = generated.spec.flows?.filter(f => f.dslName === 'RestDefinition') || [];

        if (addREST) {
            flows.push(...restG);
        } else {
            flows.push(...restE);
        }

        const routeE: CamelElement[] = existing.spec.flows?.filter(f => f.dslName === 'RouteDefinition') || [];
        const routeG: CamelElement[] = generated.spec.flows?.filter(f => f.dslName === 'RouteDefinition') || [];

        if (addRoutes) {
            flows.push(...routeG);
        } else {
            flows.push(...routeE);
        }

        existing.spec.flows = flows;
        return CamelDefinitionYaml.integrationToYaml(existing);
    };
}
