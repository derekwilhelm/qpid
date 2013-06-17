/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
define(["dojo/_base/xhr",
        "dojo/dom",
        "dojo/dom-construct",
        "dojo/_base/window",
        "dijit/registry",
        "dojo/parser",
        "dojo/_base/array",
        "dojo/_base/event",
        'dojo/_base/json',
        "dojo/store/Memory",
        "dijit/form/FilteringSelect",
        "dojo/domReady!"],
    function (xhr, dom, construct, win, registry, parser, array, event, json, Memory, FilteringSelect) {
        return {
            show: function() {
                var node = dom.byId("addVirtualHost.typeSpecificDiv");
                var that = this;

                array.forEach(registry.toArray(),
                              function(item) {
                                  if(item.id.substr(0,27) == "formAddVirtualHost.specific") {
                                      item.destroyRecursive();
                                  }
                              });

                xhr.get({url: "virtualhost/standard/add.html",
                     sync: true,
                     load:  function(data) {
                                node.innerHTML = data;
                                parser.parse(node);
                                if (that.hasOwnProperty("storeTypeChooser"))
                                {
                                    that.storeTypeChooser.destroy();
                                }
                                xhr.get({
                                    sync: true,
                                    url: "rest/helper?action=ListMessageStoreTypes",
                                    handleAs: "json"
                                }).then(
                                    function(data) {
                                        var storeTypes =  data;
                                        var storeTypesData = [];
                                        for (var i =0 ; i < storeTypes.length; i++)
                                        {
                                            storeTypesData[i]= {id: storeTypes[i], name: storeTypes[i]};
                                        }
                                        var storeTypesStore = new Memory({ data: storeTypesData });
                                        var storeTypesDiv = dom.byId("addVirtualHost.specific.selectStoreType");
                                        var input = construct.create("input", {id: "addStoreType", required: false}, storeTypesDiv);
                                        that.storeTypeChooser = new FilteringSelect({ id: "addVirtualHost.specific.storeType",
                                                                                  name: "storeType",
                                                                                  store: storeTypesStore,
                                                                                  searchAttr: "name", required: false}, input);
                                });

                     }});
            }
        };
    });
