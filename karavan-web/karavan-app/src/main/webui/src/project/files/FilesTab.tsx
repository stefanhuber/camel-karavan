import React from 'react';
import {
    Badge,
    Button,
    Bullseye,
    EmptyState,
    EmptyStateVariant,
    EmptyStateIcon,
    PageSection, PanelHeader, Panel, Tooltip, Label, EmptyStateHeader,
} from '@patternfly/react-core';
import '../../designer/karavan.css';
import {
	Tbody,
	Td,
	Th,
	Thead,
	Tr
} from '@patternfly/react-table';
import {
	Table
} from '@patternfly/react-table/deprecated';
import DeleteIcon from "@patternfly/react-icons/dist/js/icons/times-icon";
import SearchIcon from '@patternfly/react-icons/dist/esm/icons/search-icon';
import {useFilesStore, useFileStore, useProjectStore} from "../../api/ProjectStore";
import {getProjectFileType, ProjectFile, ProjectFileTypes} from "../../api/ProjectModels";
import {FileToolbar} from "./FilesToolbar";
import DownloadIcon from "@patternfly/react-icons/dist/esm/icons/download-icon";
import FileSaver from "file-saver";
import {CreateFileModal} from "./CreateFileModal";
import {DeleteFileModal} from "./DeleteFileModal";
import {UploadFileModal} from "./UploadFileModal";

export const FilesTab = () => {

    const {files} = useFilesStore();
    const {project} = useProjectStore();
    const {operation} = useFileStore();

    function getDate(lastUpdate: number): string {
        if (lastUpdate) {
            const date = new Date(lastUpdate);
            return date.toISOString().slice(0, 19).replace('T',' ');
        } else {
            return "N/A"
        }
    }

    function needCommit(lastUpdate: number): boolean {
        return lastUpdate > project.lastCommitTimestamp;
    }

    function download (file: ProjectFile) {
        if (file) {
            const type = file.name.endsWith("yaml") ? "application/yaml;charset=utf-8" : undefined;
            const f = new File([file.code], file.name, {type: type});
            FileSaver.saveAs(f);
        }
    }

    function isBuildIn(): boolean {
        return ['kamelets', 'templates', 'services'].includes(project.projectId);
    }

    function canDeleteFiles(): boolean {
        return !['templates', 'services'].includes(project.projectId);
    }

    function isKameletsProject(): boolean {
        return project.projectId === 'kamelets';
    }

    const types = isBuildIn()
        ? (isKameletsProject() ? ['KAMELET'] : ['CODE', 'PROPERTIES'])
        : ProjectFileTypes.filter(p => !['PROPERTIES', 'LOG', 'KAMELET'].includes(p.name)).map(p => p.name);

    return (
        <PageSection className="project-tab-panel" padding={{default: "padding"}}>
            <Panel>
                <PanelHeader>
                    <FileToolbar/>
                </PanelHeader>
            </Panel>
            <Table aria-label="Files" variant={"compact"} className={"table"}>
                <Thead>
                    <Tr>
                        <Th key='type' width={20}>Type</Th>
                        <Th key='filename' width={40}>Filename</Th>
                        <Th key='lastUpdate' width={30}>Updated</Th>
                        <Th key='action'></Th>
                    </Tr>
                </Thead>
                <Tbody>
                    {files.map(file => {
                        const type = getProjectFileType(file)
                        return <Tr key={file.name}>
                            <Td>
                                <Badge>{type}</Badge>
                            </Td>
                            <Td>
                                <Button style={{padding: '6px'}} variant={"link"}
                                        onClick={e =>
                                            useFileStore.setState({file: file, operation: "select"})
                                }>
                                    {file.name}
                                </Button>
                            </Td>
                            <Td>
                                {needCommit(file.lastUpdate) &&
                                    <Tooltip content="Updated after last commit" position={"right"}>
                                        <Label color="grey">{getDate(file.lastUpdate)}</Label>
                                    </Tooltip>
                                }
                                {!needCommit(file.lastUpdate) && getDate(file.lastUpdate)}
                            </Td>
                            <Td modifier={"fitContent"}>
                                {canDeleteFiles() &&
                                    <Button style={{padding: '0'}} variant={"plain"}
                                            isDisabled={file.name === 'application.properties'}
                                            onClick={e =>
                                                useFileStore.setState({file: file, operation: "delete"})
                                    }>
                                        <DeleteIcon/>
                                    </Button>
                                }
                                <Tooltip content="Download source" position={"bottom-end"}>
                                    <Button size="sm" variant="plain" icon={<DownloadIcon/>} onClick={e => download(file)}/>
                                </Tooltip>
                            </Td>
                        </Tr>
                    })}
                    {files.length === 0 &&
                        <Tr>
                            <Td colSpan={8}>
                                <Bullseye>
                                    <EmptyState variant={EmptyStateVariant.sm}>
                                        <EmptyStateHeader titleText="No results found" icon={<EmptyStateIcon icon={SearchIcon}/>} headingLevel="h2" />
                                    </EmptyState>
                                </Bullseye>
                            </Td>
                        </Tr>
                    }
                </Tbody>
            </Table>
            <CreateFileModal types={types}/>
            <UploadFileModal projectId={project.projectId} isOpen={operation === 'upload'} />
            <DeleteFileModal />
        </PageSection>
    )
}
