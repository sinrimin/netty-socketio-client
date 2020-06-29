package com.moesrc.socketio;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.netty.buffer.ByteBuf;

public class Packet implements Serializable {

    private static final long serialVersionUID = 4560159536486711426L;

    private int size;
    private PacketType type;
    private PacketType subType;
    private Long ackId;
    private String name;
    private String nsp = "";
    private Object data;

    private ByteBuf dataSource;
    private int attachmentsCount;
    private List<ByteBuf> attachments = Collections.emptyList();

    protected Packet() {
    }

    public Packet(PacketType type) {
        super();
        this.type = type;
    }

    public PacketType getSubType() {
        return subType;
    }

    public void setSubType(PacketType subType) {
        this.subType = subType;
    }

    public PacketType getType() {
        return type;
    }

    public void setData(Object data) {
        this.data = data;
    }

    public void setSize(int size) {
        this.size = size;
    }

    public int getSize() {
        return size;
    }

    /**
     * Get packet data
     *
     * @param <T> the type data
     *
     * <pre>
     * @return <b>json object</b> for PacketType.JSON type
     * <b>message</b> for PacketType.MESSAGE type
     * </pre>
     */
    public <T> T getData() {
        return (T)data;
    }

    /**
     * Creates a copy of #{@link Packet} with new namespace set
     * if it differs from current namespace.
     * Otherwise, returns original object unchanged
     */
    public Packet withNsp(String namespace) {
        if (this.nsp.equalsIgnoreCase(namespace)) {
            return this;
        } else {
            Packet newPacket = new Packet(this.type);
            newPacket.setAckId(this.ackId);
            newPacket.setData(this.data);
            newPacket.setDataSource(this.dataSource);
            newPacket.setName(this.name);
            newPacket.setSubType(this.subType);
            newPacket.setNsp(namespace);
            newPacket.attachments = this.attachments;
            newPacket.attachmentsCount = this.attachmentsCount;
            return newPacket;
        }
    }

    public void setNsp(String endpoint) {
        this.nsp = endpoint;
    }

    public String getNsp() {
        return nsp;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Long getAckId() {
        return ackId;
    }

    public void setAckId(Long ackId) {
        this.ackId = ackId;
    }

    public boolean isAckRequested() {
        return getAckId() != null;
    }

    public void initAttachments(int attachmentsCount) {
        this.attachmentsCount = attachmentsCount;
        this.attachments = new ArrayList<ByteBuf>(attachmentsCount);
    }
    public void addAttachment(ByteBuf attachment) {
        if (this.attachments.size() < attachmentsCount) {
            this.attachments.add(attachment);
        }
    }
    public List<ByteBuf> getAttachments() {
        return attachments;
    }
    public boolean hasAttachments() {
        return attachmentsCount != 0;
    }
    public boolean isAttachmentsLoaded() {
        return this.attachments.size() == attachmentsCount;
    }

    public ByteBuf getDataSource() {
        return dataSource;
    }
    public void setDataSource(ByteBuf dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public String toString() {
        StringBuffer sb = new StringBuffer("Packet [type=");
        sb.append(type);
        if (subType != null) {
            sb.append("(").append(subType).append(")");
        }
        if (name != null) {
            sb.append(", name=").append(name);
        }
        if (ackId != null) {
            sb.append(", ackId=").append(ackId);
        }
        sb.append("]");

        return sb.toString();
    }

}
