/**
 * 
 */
package io.mosip.registration.processor.stages.packet.validator;

import java.io.IOException;
import java.io.InputStream;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.mosip.registration.processor.core.abstractverticle.MessageBusAddress;
import io.mosip.registration.processor.core.abstractverticle.MessageDTO;
import io.mosip.registration.processor.core.abstractverticle.MosipEventBus;
import io.mosip.registration.processor.core.abstractverticle.MosipVerticleManager;
import io.mosip.registration.processor.core.builder.CoreAuditRequestBuilder;
import io.mosip.registration.processor.core.code.EventId;
import io.mosip.registration.processor.core.code.EventName;
import io.mosip.registration.processor.core.code.EventType;
import io.mosip.registration.processor.core.packet.dto.DemographicInfo;
import io.mosip.registration.processor.core.packet.dto.MetaData;
import io.mosip.registration.processor.core.packet.dto.PacketInfo;
import io.mosip.registration.processor.core.spi.filesystem.adapter.FileSystemAdapter;
import io.mosip.registration.processor.core.spi.filesystem.manager.FileManager;
import io.mosip.registration.processor.core.spi.packetmanager.PacketInfoManager;
import io.mosip.registration.processor.core.util.JsonUtil;
import io.mosip.registration.processor.filesystem.ceph.adapter.impl.FilesystemCephAdapterImpl;
import io.mosip.registration.processor.filesystem.ceph.adapter.impl.utils.PacketFiles;
import io.mosip.registration.processor.packet.manager.dto.DirectoryPathDto;
import io.mosip.registration.processor.stages.exception.utils.ExceptionMessages;
import io.mosip.registration.processor.stages.utils.CheckSumValidation;
import io.mosip.registration.processor.stages.utils.FilesValidation;
import io.mosip.registration.processor.stages.utils.StatusMessage;
import io.mosip.registration.processor.status.code.RegistrationStatusCode;
import io.mosip.registration.processor.status.dto.RegistrationStatusDto;
import io.mosip.registration.processor.status.service.RegistrationStatusService;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

/**
 * @author M1022006
 *
 */
@Service
public class PacketValidatorStage extends MosipVerticleManager {

	public static final String FILE_SEPARATOR = "\\";

	private static Logger log = LoggerFactory.getLogger(PacketValidatorStage.class);

	private FileSystemAdapter<InputStream, Boolean> adapter = new FilesystemCephAdapterImpl();

	private static final String USER = "MOSIP_SYSTEM";

	@Autowired
	FileManager<DirectoryPathDto, InputStream> fileManager;

	@Autowired
	RegistrationStatusService<String, RegistrationStatusDto> registrationStatusService;

	@Autowired
	private PacketInfoManager<PacketInfo, DemographicInfo, MetaData> packetInfoManager;

	/** The event id. */
	private String eventId = "";
	
	/** The event name. */
	private String eventName = "";
	
	/** The event type. */
	private String eventType = "";
	
	/** The core audit request builder. */
	@Autowired
	CoreAuditRequestBuilder coreAuditRequestBuilder;
	
	public void deployVerticle() {
		MosipEventBus mosipEventBus = this.getEventBus(this.getClass());
		this.consumeAndSend(mosipEventBus, MessageBusAddress.STRUCTURE_BUS_IN, MessageBusAddress.STRUCTURE_BUS_OUT);
	}

	@Override
	public MessageDTO process(MessageDTO object) {
		object.setMessageBusAddress(MessageBusAddress.STRUCTURE_BUS_IN);
		object.setIsValid(Boolean.FALSE);
		object.setInternalError(Boolean.FALSE);
		String registrationId = object.getRid();
		String description="";
		InputStream packetMetaInfoStream = adapter.getFile(registrationId, PacketFiles.PACKETMETAINFO.name());
		try {

			PacketInfo packetInfo = (PacketInfo) JsonUtil.inputStreamtoJavaObject(packetMetaInfoStream,
					PacketInfo.class);

			RegistrationStatusDto registrationStatusDto = registrationStatusService
					.getRegistrationStatus(registrationId);
			FilesValidation filesValidation = new FilesValidation(adapter);
			boolean isFilesValidated = filesValidation.filesValidation(registrationId, packetInfo);
			boolean isCheckSumValidated = false;
			if (isFilesValidated) {

				CheckSumValidation checkSumValidation = new CheckSumValidation(adapter);
				isCheckSumValidated = checkSumValidation.checksumvalidation(registrationId, packetInfo);
				if (!isCheckSumValidated) {
					registrationStatusDto.setStatusComment(StatusMessage.PACKET_CHECKSUM_VALIDATION);
					description="Files validation successfull and checksum validation failured in method process() of PacketValidatorStage class";
				}
			} else {
				registrationStatusDto.setStatusComment(StatusMessage.PACKET_FILES_VALIDATION);
				
				description="Files validation get failured in method process() of PacketValidatorStage";

			}
			if (isFilesValidated && isCheckSumValidated) {
				object.setIsValid(Boolean.TRUE);
				registrationStatusDto.setStatusComment(StatusMessage.PACKET_STRUCTURAL_VALIDATION);
				registrationStatusDto
						.setStatusCode(RegistrationStatusCode.PACKET_STRUCTURAL_VALIDATION_SUCCESSFULL.toString());
				packetInfoManager.savePacketData(packetInfo);
				InputStream demographicInfoStream = adapter.getFile(registrationId,
						PacketFiles.DEMOGRAPHIC.name() + FILE_SEPARATOR + PacketFiles.DEMOGRAPHICINFO.name());
				DemographicInfo demographicInfo = (DemographicInfo) JsonUtil
						.inputStreamtoJavaObject(demographicInfoStream, DemographicInfo.class);
				packetInfoManager.saveDemographicData(demographicInfo, packetInfo.getMetaData());
				description="PacketInfo & DemographicData has been saved successfully in method process() of PacketValidatorStage class";
				

			} else {
				object.setIsValid(Boolean.FALSE);
				if (registrationStatusDto.getRetryCount() == null) {
					registrationStatusDto.setRetryCount(0);
				} else {
					registrationStatusDto.setRetryCount(registrationStatusDto.getRetryCount() + 1);
				}

				registrationStatusDto
						.setStatusCode(RegistrationStatusCode.PACKET_STRUCTURAL_VALIDATION_FAILED.toString());
				description="Files validation and checksum validation get failured in method process() of PacketValidatorStage class";

			}
			eventId = EventId.RPR_402.toString();
			eventName = EventName.UPDATE.toString();
			eventType = EventType.BUSINESS.toString();
			registrationStatusDto.setUpdatedBy(USER);
			registrationStatusService.updateRegistrationStatus(registrationStatusDto);

		} catch (IOException e) {
			log.error(ExceptionMessages.STRUCTURAL_VALIDATION_FAILED.name(), e);
			object.setInternalError(Boolean.TRUE);
			description="IOException occurs in method process() of PacketValidatorStage class";
			eventId = EventId.RPR_405.toString();
			eventName = EventName.EXCEPTION.toString();
			eventType = EventType.SYSTEM.toString();

		} catch (Exception ex) {
			log.error(ExceptionMessages.STRUCTURAL_VALIDATION_FAILED.name(), ex);
			object.setInternalError(Boolean.TRUE);
			description="Unknown exception occurs in method process() of PacketValidatorStage class";
			eventId = EventId.RPR_405.toString();
			eventName = EventName.EXCEPTION.toString();
			eventType = EventType.SYSTEM.toString();
		}finally {
			
			coreAuditRequestBuilder.createAuditRequestBuilder(description, eventId, eventName, eventType,registrationId);
			
		}

		return object;
	}

}
